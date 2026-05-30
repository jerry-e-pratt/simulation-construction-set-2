package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import java.nio.IntBuffer;

import javafx.scene.image.PixelBuffer;
import javafx.scene.image.WritableImage;

/**
 * This class is used for debugging timestamp delays in the videos when viewing them in SCS2. In order to see the timestamp debugging information change the
 * boolean in {@link VideoViewer#LOGGER_VIDEO_DEBUG} to true. This can also be set as an environmental variable.
 */
public class FrameData
{
   public WritableImage frame;
   /**
    * Non-null when {@link #frame} is backed by a {@link PixelBuffer}; the producer writes pixels into the buffer and the
    * consumer must invoke {@link PixelBuffer#updateBuffer(javafx.util.Callback)} on the JavaFX Application Thread to mark
    * the image dirty for the next pulse. Null for readers that populate {@link #frame} via {@code PixelWriter.setPixels}.
    */
   public PixelBuffer<IntBuffer> pixelBuffer;
   public long queryRobotTimestamp;
   public long currentRobotTimestamp;
   public long currentVideoTimestamp;
   public long currentDemuxerTimestamp;
}
