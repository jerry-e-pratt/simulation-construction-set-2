package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import us.ihmc.robotDataLogger.Camera;
import us.ihmc.scs2.session.log.ProgressConsumer;
import us.ihmc.scs2.session.log.TimestampScrubber;

import java.io.File;
import java.io.IOException;

/**
 * This interface allows supporting different types of capture methods to be viewed back with SCS2.
 */
public interface VideoDataReader
{

   int getImageHeight();

   int getImageWidth();

   void readVideoFrame(long timestamp);

   void cropVideo(File outputFile, File timestampFile, long startTimestamp, long endTimestamp, ProgressConsumer monitor) throws IOException;

   String getName();

   Camera getCamera();

   default FrameData pollCurrentFrame()
   {
      return null;
   };

   int getCurrentIndex();

   boolean replacedRobotTimestampsContainsIndex(int index);

   default TimestampScrubber getTimestampScrubber()
   {
      return null;
   }

   /**
    * Rolling estimate of how many image-bearing video frames per second the reader is producing
    * (i.e. how often {@link #readVideoFrame(long)} actually decodes a new frame, ignoring early
    * returns when the requested PTS matched the previous read). Returns {@link Double#NaN} when
    * the reader does not track this metric or has not yet produced enough samples.
    */
   default double getDecodeRateHz()
   {
      return Double.NaN;
   }

   /**
    * EWMA estimate of the wall-clock time spent inside a single decode-bearing
    * {@link #readVideoFrame(long)} call, in milliseconds. Returns {@link Double#NaN} when the
    * reader does not track this metric or has not yet produced any samples.
    */
   default double getDecodeTimeMillis()
   {
      return Double.NaN;
   }

   /**
    * Nominal frame rate reported by the underlying video container, in Hz. Returns
    * {@link Double#NaN} when the reader does not expose this.
    */
   default double getSourceFrameRateHz()
   {
      return Double.NaN;
   }
}
