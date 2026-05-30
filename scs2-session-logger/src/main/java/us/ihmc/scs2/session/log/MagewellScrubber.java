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

   private final MagewellDemuxerLike magewellDemuxer;

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

   /**
    * Fallback frame period used to derive the streaming catch-up threshold when the demuxer reports an
    * unusable frame rate (<= 0 or NaN). 30 fps matches the most common Magewell capture rate.
    */
   private static final double FALLBACK_FRAME_RATE_FPS = 30.0;

   /**
    * Multiplier on the source frame period that determines when streaming playback switches from
    * single-frame advance to a multi-frame catch-up. In steady-state 1x playback the per-call
    * forward delta is exactly one frame period, so 1.5 absorbs sub-frame scheduling jitter while
    * still catching up before lag has any chance to compound into the seek-tolerance window.
    */
   private static final double STREAMING_CATCHUP_THRESHOLD_FRAMES = 1.5;

   /**
    * Threshold (microseconds) at or below which the streaming branch advances a single frame; above
    * this and within {@link #FORWARD_PLAYBACK_TOLERANCE_US}, the streaming branch catches up to the
    * requested PTS via {@link #advanceToVideoFrameAtOrAfter(long)}. Computed once from the source
    * frame rate so the threshold tracks 30 fps vs. 60 fps captures.
    */
   private final long streamingCatchupThresholdUS;

   public MagewellScrubber(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      this(camera, dataDirectory, hasTimeBase, defaultSoftwareDemuxer(dataDirectory, camera));
   }

   /**
    * Demuxer-injecting overload used by NVDEC-backed subclasses. The supplied {@code demuxer} replaces
    * the software {@link MagewellDemuxer} that the public constructor would have built; everything else
    * (timestamp scrubber, delay tracking, seek-vs-stream policy) is identical.
    */
   protected MagewellScrubber(Camera camera, File dataDirectory, boolean hasTimeBase, MagewellDemuxerLike demuxer) throws IOException
   {
      this.camera = camera;
      name = camera.getNameAsString();
      boolean interlaced = camera.getInterlaced();

      if (!hasTimeBase)
      {
         System.err.println("Video data is using timestamps instead of frame numbers. Falling back to seeking based on timestamp.");
      }

      magewellDemuxer = demuxer;

      File timestampFile = new File(dataDirectory, camera.getTimestampFileAsString());
      this.timestampScrubber = new TimestampScrubber(timestampFile, hasTimeBase, interlaced);

      double frameRate = demuxer.getFrameRate();
      if (!(frameRate > 0.0) || Double.isInfinite(frameRate) || Double.isNaN(frameRate))
         frameRate = FALLBACK_FRAME_RATE_FPS;
      streamingCatchupThresholdUS = (long) (STREAMING_CATCHUP_THRESHOLD_FRAMES * 1_000_000.0 / frameRate);
   }

   private static MagewellDemuxerLike defaultSoftwareDemuxer(File dataDirectory, Camera camera) throws IOException
   {
      File videoFile = new File(dataDirectory, camera.getVideoFileAsString());
      if (!videoFile.exists())
      {
         throw new IOException("Cannot find video: " + videoFile);
      }
      return new UpstreamMagewellDemuxerAdapter(new MagewellDemuxer(videoFile));
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
      boolean needsSeek = lastReadVideoTimestamp == Long.MIN_VALUE || forwardDelta < 0 || forwardDelta > FORWARD_PLAYBACK_TOLERANCE_US;

      Frame frame;
      if (needsSeek)
      {
         // Seek / scrub branch: jump to the nearest preceding keyframe, then catch up to the
         // requested PTS so the returned frame covers the user-visible tick exactly. Necessary
         // for random-access scrubs and the first read; play and scrub then land on the same
         // frame for the same robot timestamp.
         magewellDemuxer.seekToPTS(currentVideoTimestamp);
         frame = advanceToVideoFrameAtOrAfter(currentVideoTimestamp);
      }
      else if (forwardDelta > streamingCatchupThresholdUS)
      {
         // Catch-up branch: forward delta exceeds one frame period (with jitter slack) but is
         // still inside the seek tolerance, so we drain video frames up to the requested PTS
         // without a keyframe seek. This prevents single-frame lag from compounding across calls
         // until it crosses FORWARD_PLAYBACK_TOLERANCE_US and triggers a visible 250-ms jump:
         // any intermediate frames the session already passed are dropped silently instead.
         frame = advanceToVideoFrameAtOrAfter(currentVideoTimestamp);
      }
      else
      {
         // Streaming branch: requested PTS is within one frame period of the decoder's current
         // position, i.e. steady-state sequential playback. Return exactly one image-bearing
         // frame per call so every decoded frame reaches the screen at 1x speed instead of the
         // catch-up loop throwing N-1 frames away every poll.
         frame = advanceOneVideoFrame();
      }

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

   /**
    * Streaming-branch helper: pulls packets until the next image-bearing {@link Frame} appears
    * and returns it (skipping interleaved audio / timecode packets). Used during sequential
    * forward playback so every decoded video frame reaches the screen — no target-landing,
    * no catch-up loop. Returns {@code null} on EOF or if the safety cap is hit before an image
    * frame appears.
    */
   private Frame advanceOneVideoFrame()
   {
      int packetsRead = 0;
      while (packetsRead < MAX_PACKETS_PER_FRAME_READ)
      {
         Frame frame = magewellDemuxer.getNextFrame();
         if (frame == null)
            return null;
         packetsRead++;
         if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0)
            continue;
         return frame;
      }
      return null;
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

   private static long getFrameAtTimestamp(long endCameraTimestamp, MagewellDemuxerLike magewellDemuxer)
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

   public MagewellDemuxerLike getMagewellDemuxer()
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
