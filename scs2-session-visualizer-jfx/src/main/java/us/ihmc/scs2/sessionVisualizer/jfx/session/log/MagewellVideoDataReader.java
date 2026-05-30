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
   private final JavaFXFrameConverter frameConverter = new JavaFXFrameConverter();

   public MagewellVideoDataReader(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      magewellScrubber = new MagewellScrubber(camera, dataDirectory, hasTimeBase);
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
   }

   /**
    * Decodes {@code frameToConvert} directly into the {@link PixelBuffer} backing the slot's {@link WritableImage}.
    * Allocates (or reallocates) the buffer-backed image when the slot is empty or the frame dimensions changed.
    * The pixel writes happen here (any thread); the consumer must call {@link PixelBuffer#updateBuffer} on the
    * JavaFX Application Thread to publish the change for the next pulse.
    */
   private void writeFrameIntoSlot(Frame frameToConvert, FrameData slot)
   {
      Image currentImage = frameConverter.convert(frameToConvert);
      int width = (int) currentImage.getWidth();
      int height = (int) currentImage.getHeight();

      if (slot.pixelBuffer == null || (int) slot.frame.getWidth() != width || (int) slot.frame.getHeight() != height)
      {
         IntBuffer backing = ByteBuffer.allocateDirect(width * height * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
         slot.pixelBuffer = new PixelBuffer<>(width, height, backing, ARGB_PRE_PIXEL_FORMAT);
         slot.frame = new WritableImage(slot.pixelBuffer);
      }

      currentImage.getPixelReader().getPixels(0, 0, width, height, ARGB_PRE_PIXEL_FORMAT, slot.pixelBuffer.getBuffer(), width);
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
