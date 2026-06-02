package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.scene.image.WritableImage;

/**
 * This class is used for debugging timestamp delays in the videos when viewing them in SCS2. In order to see the timestamp debugging information change the
 * boolean in {@link VideoViewer#LOGGER_VIDEO_DEBUG} to true. This can also be set as an environmental variable.
 */
public class FrameData
{
   public WritableImage frame;
   /**
    * Non-null when {@link #frame} was sourced from a {@link VideoFrameBufferPool}. Producers transfer one reference to
    * the slot on commit; consumers that need the pixels to remain stable past the next poll must call
    * {@link VideoFrameBufferPool.FrameBuffer#retain()} and release later. Null for readers that allocate or convert
    * images directly (in which case {@link #frame} is updated via {@code PixelWriter.setPixels} and no FX-thread
    * dirty-marking call is required).
    */
   public VideoFrameBufferPool.FrameBuffer frameBuffer;
   public long queryRobotTimestamp;
   public long currentRobotTimestamp;
   public long currentVideoTimestamp;
   public long currentDemuxerTimestamp;
}
