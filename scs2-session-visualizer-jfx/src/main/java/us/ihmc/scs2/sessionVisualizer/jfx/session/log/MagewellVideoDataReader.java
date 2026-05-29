package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
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

public class MagewellVideoDataReader implements VideoDataReader
{
   private final MagewellScrubber magewellScrubber;
   private final ConcurrentCopier<FrameData> imageBuffer = new ConcurrentCopier<>(FrameData::new);
   private static final WritablePixelFormat<java.nio.IntBuffer> ARGB_PIXEL_FORMAT = PixelFormat.getIntArgbInstance();
   private final JavaFXFrameConverter frameConverter = new JavaFXFrameConverter();
   private int[] pixelBuffer = null;

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
      copyForWriting.frame = convertFrameToWritableImage(nextFrame, copyForWriting.frame);
      imageBuffer.commit();
   }

   /**
    * Converts a {@link Frame} to a {@link WritableImage} for display in JavaFX, reusing the provided
    * {@code reusable} image when its dimensions match the converted frame.
    *
    * @param frameToConvert the next frame to visualize.
    * @param reusable       the previously returned image for this buffer slot, or {@code null} on first use.
    * @return the populated {@link WritableImage}; {@code reusable} when dimensions match, otherwise a new instance.
    */
   public WritableImage convertFrameToWritableImage(Frame frameToConvert, WritableImage reusable)
   {
      Image currentImage = frameConverter.convert(frameToConvert);
      int width = (int) currentImage.getWidth();
      int height = (int) currentImage.getHeight();

      WritableImage writableImage = reusable;
      if (writableImage == null || (int) writableImage.getWidth() != width || (int) writableImage.getHeight() != height)
         writableImage = new WritableImage(width, height);

      PixelReader pixelReader = currentImage.getPixelReader();
      PixelWriter pixelWriter = writableImage.getPixelWriter();

      int required = width * height;
      if (pixelBuffer == null || pixelBuffer.length < required)
         pixelBuffer = new int[required];

      pixelReader.getPixels(0, 0, width, height, ARGB_PIXEL_FORMAT, pixelBuffer, 0, width);
      pixelWriter.setPixels(0, 0, width, height, ARGB_PIXEL_FORMAT, pixelBuffer, 0, width);

      return writableImage;
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
