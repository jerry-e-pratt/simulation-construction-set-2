package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;
import us.ihmc.robotDataLogger.Camera;
import us.ihmc.scs2.session.log.MagewellScrubber;
import us.ihmc.scs2.session.log.ProgressConsumer;
import us.ihmc.scs2.session.log.TimestampScrubber;

import java.io.File;
import java.io.IOException;

public class MagewellVideoDataReader implements VideoDataReader
{
   private final MagewellScrubber magewellScrubber;
   private final FrameData frameData = new FrameData();
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

   /** Cap on consecutive non-video packets to skip per seek (audio/timecode interleaved with video). */
   private static final int MAX_NON_VIDEO_FRAMES_TO_SKIP = 256;

   public void readVideoFrame(long queryRobotTimestamp)
   {
      Frame nextFrame = magewellScrubber.readVideoFrame(queryRobotTimestamp);

      // Scrubber returns null when the requested robot timestamp maps to the same video PTS as the last
      // read (data sampling rate exceeds video frame rate); keep displaying the previously decoded frame.
      if (nextFrame == null)
         return;

      // The underlying FFmpegFrameGrabber.grabFrame() returns the next packet from any stream,
      // so a multi-stream MP4 (video + audio + timecode) may yield non-image frames here.
      int skipped = 0;
      while (nextFrame != null && !hasImageData(nextFrame) && skipped < MAX_NON_VIDEO_FRAMES_TO_SKIP)
      {
         nextFrame = magewellScrubber.getMagewellDemuxer().getNextFrame();
         skipped++;
      }

      // This is a copy that can be shown in the video view to debug timestamp issues
      {
         FrameData copyForWriting = frameData;
         copyForWriting.queryRobotTimestamp = queryRobotTimestamp;
         copyForWriting.currentRobotTimestamp = magewellScrubber.getCurrentRobotTimestamp();
         copyForWriting.currentVideoTimestamp = magewellScrubber.getCurrentVideoTimestamp();
         copyForWriting.currentDemuxerTimestamp = magewellScrubber.getMagewellDemuxer().getCurrentPTS();
      }

      frameData.frame = convertFrameToWritableImage(nextFrame);
   }

   private static boolean hasImageData(Frame frame)
   {
      return frame.image != null && frame.imageWidth > 0 && frame.imageHeight > 0;
   }

   /**
    * This class converts a {@link Frame} to a {@link WritableImage} in order to be displayed correctly in JavaFX.
    *
    * @param frameToConvert is the next frame we want to visualize so we convert it to be compatible with JavaFX
    * @return {@link WritableImage}
    */
   public WritableImage convertFrameToWritableImage(Frame frameToConvert)
   {
      Image currentImage;

      if (frameToConvert == null || !hasImageData(frameToConvert))
      {
         return null;
      }

      currentImage = frameConverter.convert(frameToConvert);
      int width = (int) currentImage.getWidth();
      int height = (int) currentImage.getHeight();
      WritableImage writableImage = new WritableImage(width, height);
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
      return frameData;
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
