package us.ihmc.scs2.session.log;

import org.bytedeco.javacv.Frame;
import us.ihmc.robotDataLogger.Camera;
import us.ihmc.robotDataLogger.logger.MagewellDemuxer;
import us.ihmc.robotDataLogger.logger.MagewellMuxer;
import us.ihmc.tools.CaptureTimeTools;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Provides support for scrubbing images from .mov files recorded with the Magewell logger.
 */
public class MagewellScrubber
{
   private final TimestampScrubber timestampScrubber;
   private final String name;

   private final MagewellDemuxer magewellDemuxer;

   private final Camera camera;
   private long currentVideoTimestamp;
   private long currentRobotTimestamp;
   private long lastReadVideoTimestamp = Long.MIN_VALUE;
   private long lastSeenDelay;

   /**
    * Tolerance (in microseconds) within which we advance the decoder by streaming frames instead of seeking.
    * FFmpegFrameGrabber.setTimestamp() seeks to the nearest preceding keyframe and re-decodes the GOP, which
    * is prohibitively expensive when called for every playback frame; for sequential forward playback we
    * instead let the decoder progress naturally via grabFrame(). 250 ms ≈ 7 frames at 30 fps, which keeps
    * normal playback and modest fast-forward on the streaming path while forwarding larger scrub jumps to
    * a fresh keyframe seek.
    */
   private static final long FORWARD_PLAYBACK_TOLERANCE_US = 250_000L;

   /**
    * Safety cap on packets consumed by a single {@link #readVideoFrame(long)} call. Magewell MP4s
    * interleave audio packets (~94/s) with video frames (~30/s), so a forward catch-up of one second
    * may require on the order of a hundred packets; this bound prevents pathological loops on broken
    * streams while comfortably covering legitimate forward jumps inside the tolerance window.
    */
   private static final int MAX_PACKETS_PER_FRAME_READ = 1024;

   public MagewellScrubber(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      this.camera = camera;
      name = camera.getNameAsString();
      boolean interlaced = camera.getInterlaced();

      if (!hasTimeBase)
      {
         System.err.println("Video data is using timestamps instead of frame numbers. Falling back to seeking based on timestamp.");
      }

      File videoFile = new File(dataDirectory, camera.getVideoFileAsString());

      if (!videoFile.exists())
      {
         throw new IOException("Cannot find video: " + videoFile);
      }

      magewellDemuxer = new MagewellDemuxer(videoFile);

      File timestampFile = new File(dataDirectory, camera.getTimestampFileAsString());
      this.timestampScrubber = new TimestampScrubber(timestampFile, hasTimeBase, interlaced);
   }

   public int getImageHeight()
   {
      return magewellDemuxer.getImageHeight();
   }

   public int getImageWidth()
   {
      return magewellDemuxer.getImageWidth();
   }

   public Frame readVideoFrame(long queryRobotTimestamp)
   {
      long currentDelay = timestampScrubber.getDelay();
      if (currentDelay != lastSeenDelay)
      {
         lastReadVideoTimestamp = Long.MIN_VALUE;
         lastSeenDelay = currentDelay;
      }

      currentVideoTimestamp = timestampScrubber.getVideoTimestampFromRobotTimestamp(queryRobotTimestamp);
      currentRobotTimestamp = timestampScrubber.getCurrentRobotTimestamp();

      if (currentVideoTimestamp == lastReadVideoTimestamp)
         return null;

      // Seek-vs-stream is decided from the demuxer's actual position. Comparing two consecutive
      // *requested* PTS values let the decoder fall arbitrarily far behind on forward scrubs inside
      // the tolerance window, so play and scrub landed on different frames for the same robot tick.
      long demuxerPTS = magewellDemuxer.getCurrentPTS();
      long forwardDelta = currentVideoTimestamp - demuxerPTS;
      if (lastReadVideoTimestamp == Long.MIN_VALUE || forwardDelta < 0 || forwardDelta > FORWARD_PLAYBACK_TOLERANCE_US)
      {
         magewellDemuxer.seekToPTS(currentVideoTimestamp);
      }

      Frame frame = advanceToVideoFrameAtOrAfter(currentVideoTimestamp);

      lastReadVideoTimestamp = currentVideoTimestamp;
      return frame;
   }

   /**
    * Advances the demuxer until it yields an image-bearing {@link Frame} whose PTS is at or past
    * {@code targetPTS}. Interleaved non-video packets (audio, timecode) are skipped and video
    * frames still behind the target are stepped past, so the returned frame is the first video
    * frame that covers the requested PTS. Returns the most recent image frame seen on EOF, or
    * {@code null} when no image frame was encountered before EOF or the safety cap.
    */
   private Frame advanceToVideoFrameAtOrAfter(long targetPTS)
   {
      Frame lastImageFrame = null;
      int packetsRead = 0;
      while (packetsRead < MAX_PACKETS_PER_FRAME_READ)
      {
         Frame frame = magewellDemuxer.getNextFrame();
         if (frame == null)
            break;
         packetsRead++;
         if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0)
            continue;
         lastImageFrame = frame;
         if (frame.timestamp >= targetPTS)
            return frame;
      }
      return lastImageFrame;
   }

   public void cropVideo(File outputFile, File timestampFile, long startTimestamp, long endTimestamp, ProgressConsumer progressConsumer) throws IOException
   {
      long startVideoTimestamp = timestampScrubber.getVideoTimestampFromRobotTimestamp(startTimestamp);
      long endVideoTimestamp = timestampScrubber.getVideoTimestampFromRobotTimestamp(endTimestamp);

      long[] robotTimestampsForCroppedLog = timestampScrubber.getCroppedRobotTimestamps(startTimestamp, endTimestamp);
      long[] videoTimestampsForCroppedLog = new long[robotTimestampsForCroppedLog.length];
      int i = 0;

      // This stuff is used to print to SCS2 so the user knows how the cropped log is going, progress wise
      long startFrame = getFrameAtTimestamp(startVideoTimestamp, magewellDemuxer); // This also moves the stream to the startFrame
      long endFrame = getFrameAtTimestamp(endVideoTimestamp, magewellDemuxer);
      long numberOfFrames = endFrame - startFrame;
      int frameRate = (int) magewellDemuxer.getFrameRate();

      magewellDemuxer.seekToPTS(startVideoTimestamp);

      PrintWriter timestampWriter = new PrintWriter(timestampFile);
      timestampWriter.println(1 + "\n" + frameRate);

      long startTime = System.currentTimeMillis();

      MagewellMuxer magewellMuxer = new MagewellMuxer(outputFile, magewellDemuxer.getImageWidth(), magewellDemuxer.getImageHeight());
      magewellMuxer.start();

      Frame frame;
      while ((frame = magewellDemuxer.getNextFrame()) != null && magewellDemuxer.getFrameNumber() <= endFrame)
      {
         // We want to write all the frames at once to get equal timestamps between frames. When recording from the camera we have a fixed rate at which we
         // receive frames, so we don't need to worry about it, here however, we don't have that so we cna grab the next frame as fast as possible. However if the
         // timestamps between frames aren't large enough, things won't work. (maybe :))
         long videoTimestamp = CaptureTimeTools.timeSinceStartedCaptureInMicroseconds(System.currentTimeMillis(), startTime);
         magewellMuxer.recordFrame(frame, videoTimestamp);
         videoTimestampsForCroppedLog[i] = magewellMuxer.getTimeStamp();
         i++;

         if (progressConsumer != null)
         {
            progressConsumer.info("frame %d/%d".formatted(magewellDemuxer.getFrameNumber() - startFrame, numberOfFrames));
            progressConsumer.progress((double) (magewellDemuxer.getFrameNumber() - startFrame) / (double) numberOfFrames);
         }
      }

      for (i = 0; i < videoTimestampsForCroppedLog.length; i++)
      {
         timestampWriter.print(robotTimestampsForCroppedLog[i]);
         timestampWriter.print(" ");
         timestampWriter.println(videoTimestampsForCroppedLog[i]);
      }

      magewellMuxer.close();
      timestampWriter.close();
   }

   private static long getFrameAtTimestamp(long endCameraTimestamp, MagewellDemuxer magewellDemuxer)
   {
      magewellDemuxer.seekToPTS(endCameraTimestamp);
      return magewellDemuxer.getFrameNumber();
   }

   public long getCurrentRobotTimestamp()
   {
      return currentRobotTimestamp;
   }

   public long getCurrentVideoTimestamp()
   {
      return currentVideoTimestamp;
   }

   public TimestampScrubber getTimestampScrubber()
   {
      return timestampScrubber;
   }

   public MagewellDemuxer getMagewellDemuxer()
   {
      return magewellDemuxer;
   }

   public String getName()
   {
      return name;
   }

   public Camera getCamera()
   {
      return camera;
   }

   public int getCurrentIndex()
   {
      return timestampScrubber.getCurrentIndex();
   }

   public boolean replacedRobotTimestampsContainsIndex(int index)
   {
      return timestampScrubber.getReplacedRobotTimestampIndex(index);
   }
}
