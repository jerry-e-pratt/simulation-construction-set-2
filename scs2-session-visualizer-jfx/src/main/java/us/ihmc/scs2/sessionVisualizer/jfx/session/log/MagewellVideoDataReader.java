package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.scene.image.Image;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;
import us.ihmc.concurrent.ConcurrentCopier;
import us.ihmc.robotDataLogger.Camera;
import us.ihmc.scs2.session.log.MagewellScrubber;
import us.ihmc.scs2.session.log.ProgressConsumer;
import us.ihmc.scs2.session.log.TimestampScrubber;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class MagewellVideoDataReader implements VideoDataReader
{
   private final MagewellScrubber magewellScrubber;
   private final ConcurrentCopier<FrameData> imageBuffer = new ConcurrentCopier<>(FrameData::new);
   // PixelBuffer requires premultiplied alpha; video frames are opaque so this is a no-op vs. non-premultiplied.
   private static final WritablePixelFormat<IntBuffer> ARGB_PRE_PIXEL_FORMAT = PixelFormat.getIntArgbPreInstance();
   // Fast-path BGRA->IntArgbPre aliasing depends on B,G,R,A native bytes packing into a 0xAARRGGBB int. True on
   // little-endian hosts (x86_64, aarch64); on big-endian we fall back to the JavaFXFrameConverter path.
   private static final boolean NATIVE_BYTE_ORDER_IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
   private final JavaFXFrameConverter frameConverter = new JavaFXFrameConverter();

   // Decode-throughput instrumentation. Written by the background decode thread inside
   // readVideoFrame; read by the FX thread via the VideoDataReader stats getters. Only decode-
   // bearing calls (those that produced a fresh image frame) update the metrics; early-return
   // calls where the requested PTS matched the previous read are excluded so the rate / time
   // reflect actual decoder work rather than poll frequency.
   private static final double DECODE_TIME_EWMA_ALPHA = 0.2;
   private static final long DECODE_RATE_WINDOW_NANOS = 1_000_000_000L;
   private volatile double decodeTimeMillisEwma = Double.NaN;
   private volatile double decodeRateHz = Double.NaN;
   private long decodeWindowStartNanos = 0L;
   private int decodesInWindow = 0;

   public MagewellVideoDataReader(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      this(new MagewellScrubber(camera, dataDirectory, hasTimeBase));
   }

   /**
    * Scrubber-injecting overload for subclasses that supply a non-default scrubber (e.g. NVDEC-backed).
    */
   protected MagewellVideoDataReader(MagewellScrubber magewellScrubber)
   {
      this.magewellScrubber = magewellScrubber;
   }

   public int getImageHeight()
   {
      return magewellScrubber.getMagewellDemuxer().getImageHeight();
   }

   public int getImageWidth()
   {
      return magewellScrubber.getMagewellDemuxer().getImageWidth();
   }

   public void readVideoFrame(long queryRobotTimestamp)
   {
      // The scrubber's contract: either an image-bearing Frame whose PTS is at or past the requested
      // video timestamp, or null when the requested PTS matches the previous read (data rate exceeds
      // video frame rate) or no image frame was found before EOF / the safety cap. In every null case
      // we keep displaying the previously decoded frame.
      long decodeStartNanos = System.nanoTime();
      Frame nextFrame = magewellScrubber.readVideoFrame(queryRobotTimestamp);
      if (nextFrame == null)
         return;

      FrameData copyForWriting = imageBuffer.getCopyForWriting();
      copyForWriting.queryRobotTimestamp = queryRobotTimestamp;
      copyForWriting.currentRobotTimestamp = magewellScrubber.getCurrentRobotTimestamp();
      copyForWriting.currentVideoTimestamp = magewellScrubber.getCurrentVideoTimestamp();
      copyForWriting.currentDemuxerTimestamp = magewellScrubber.getMagewellDemuxer().getCurrentPTS();
      writeFrameIntoSlot(nextFrame, copyForWriting);
      imageBuffer.commit();
      updateDecodeStatistics(decodeStartNanos);
   }

   private void updateDecodeStatistics(long decodeStartNanos)
   {
      long nowNanos = System.nanoTime();
      double elapsedMillis = (nowNanos - decodeStartNanos) / 1_000_000.0;
      double previous = decodeTimeMillisEwma;
      decodeTimeMillisEwma = Double.isNaN(previous) ? elapsedMillis : DECODE_TIME_EWMA_ALPHA * elapsedMillis + (1.0 - DECODE_TIME_EWMA_ALPHA) * previous;

      if (decodeWindowStartNanos == 0L)
         decodeWindowStartNanos = nowNanos;
      decodesInWindow++;
      long windowElapsedNanos = nowNanos - decodeWindowStartNanos;
      if (windowElapsedNanos >= DECODE_RATE_WINDOW_NANOS)
      {
         decodeRateHz = decodesInWindow * 1_000_000_000.0 / windowElapsedNanos;
         decodeWindowStartNanos = nowNanos;
         decodesInWindow = 0;
      }
   }

   @Override
   public double getDecodeRateHz()
   {
      return decodeRateHz;
   }

   @Override
   public double getDecodeTimeMillis()
   {
      return decodeTimeMillisEwma;
   }

   @Override
   public double getSourceFrameRateHz()
   {
      return magewellScrubber.getMagewellDemuxer().getFrameRate();
   }

   /**
    * Decodes {@code frameToConvert} directly into the {@link PixelBuffer} backing the slot's {@link WritableImage}.
    * Allocates (or reallocates) the buffer-backed image when the slot is empty or the frame dimensions changed.
    * The pixel writes happen here (any thread); the consumer must call {@link PixelBuffer#updateBuffer} on the
    * JavaFX Application Thread to publish the change for the next pulse.
    * <p>
    * When the upstream grabber is configured to deliver packed 4-channel byte frames (BGRA, as set on the NVDEC
    * demuxer's grabber), the bytes already match JavaFX's IntArgbPre layout on little-endian hosts and we copy them
    * straight into the slot's direct ByteBuffer, skipping {@code JavaFXFrameConverter}'s
    * {@code BufferedImage -> WritableImage -> getPixels} round-trip. Planar / non-byte frames (e.g. NV12 from the
    * software MagewellDemuxer) take the converter fallback unchanged.
    */
   private void writeFrameIntoSlot(Frame frameToConvert, FrameData slot)
   {
      if (NATIVE_BYTE_ORDER_IS_LITTLE_ENDIAN
          && frameToConvert.image != null
          && frameToConvert.image.length == 1
          && frameToConvert.imageChannels == 4
          && frameToConvert.image[0] instanceof ByteBuffer sourceBytes)
      {
         writePackedBgraIntoSlot(frameToConvert, sourceBytes, slot);
         return;
      }

      Image currentImage = frameConverter.convert(frameToConvert);
      int width = (int) currentImage.getWidth();
      int height = (int) currentImage.getHeight();

      if (slot.pixelBuffer == null || (int) slot.frame.getWidth() != width || (int) slot.frame.getHeight() != height)
      {
         ByteBuffer backingBytes = ByteBuffer.allocateDirect(width * height * Integer.BYTES).order(ByteOrder.nativeOrder());
         slot.pixelByteBuffer = backingBytes;
         slot.pixelBuffer = new PixelBuffer<>(width, height, backingBytes.asIntBuffer(), ARGB_PRE_PIXEL_FORMAT);
         slot.frame = new WritableImage(slot.pixelBuffer);
      }

      currentImage.getPixelReader().getPixels(0, 0, width, height, ARGB_PRE_PIXEL_FORMAT, slot.pixelBuffer.getBuffer(), width);
   }

   /**
    * Fast path for packed BGRA frames. {@code frame.imageStride} is the row stride in bytes (from FFmpeg's
    * {@code AVFrame.linesize[0]}); when the source rows are tightly packed we do a single bulk transfer, otherwise
    * we copy row-by-row to handle padded rows.
    */
   private void writePackedBgraIntoSlot(Frame frameToConvert, ByteBuffer sourceBytes, FrameData slot)
   {
      int width = frameToConvert.imageWidth;
      int height = frameToConvert.imageHeight;
      int sourceStrideBytes = frameToConvert.imageStride;
      int destStrideBytes = width * Integer.BYTES;

      if (slot.pixelBuffer == null || (int) slot.frame.getWidth() != width || (int) slot.frame.getHeight() != height)
      {
         ByteBuffer backingBytes = ByteBuffer.allocateDirect(width * height * Integer.BYTES).order(ByteOrder.nativeOrder());
         slot.pixelByteBuffer = backingBytes;
         slot.pixelBuffer = new PixelBuffer<>(width, height, backingBytes.asIntBuffer(), ARGB_PRE_PIXEL_FORMAT);
         slot.frame = new WritableImage(slot.pixelBuffer);
      }

      ByteBuffer source = sourceBytes.duplicate();
      ByteBuffer destination = slot.pixelByteBuffer;
      destination.clear();

      if (sourceStrideBytes == destStrideBytes)
      {
         source.position(0).limit(destStrideBytes * height);
         destination.put(source);
      }
      else
      {
         for (int y = 0; y < height; y++)
         {
            source.limit(y * sourceStrideBytes + destStrideBytes).position(y * sourceStrideBytes);
            destination.put(source);
         }
      }
   }

   public void cropVideo(File outputFile, File timestampFile, long startTimestamp, long endTimestamp, ProgressConsumer progressConsumer) throws IOException
   {
      magewellScrubber.cropVideo(outputFile, timestampFile, startTimestamp, endTimestamp, progressConsumer);
   }

   public String getName()
   {
      return magewellScrubber.getName();
   }

   public Camera getCamera()
   {
      return magewellScrubber.getCamera();
   }

   public FrameData pollCurrentFrame()
   {
      return imageBuffer.getCopyForReading();
   }

   public int getCurrentIndex()
   {
      return magewellScrubber.getCurrentIndex();
   }

   public boolean replacedRobotTimestampsContainsIndex(int index)
   {
      return magewellScrubber.replacedRobotTimestampsContainsIndex(index);
   }

   @Override
   public TimestampScrubber getTimestampScrubber()
   {
      return magewellScrubber.getTimestampScrubber();
   }
}
