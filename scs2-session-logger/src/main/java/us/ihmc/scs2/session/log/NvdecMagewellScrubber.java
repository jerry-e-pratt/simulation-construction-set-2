package us.ihmc.scs2.session.log;

import org.bytedeco.javacv.FrameGrabber;
import logger_msgs.Camera;

import java.io.File;
import java.io.IOException;

/**
 * {@link MagewellScrubber} variant whose demuxer runs HEVC/H.264 decode on the GPU via {@link NvdecMagewellDemuxer}.
 * The seek-vs-stream policy and timestamp scrubbing logic are inherited unchanged from the superclass; only the
 * decoder backing the demuxer differs. If the NVDEC demuxer cannot be opened (no NVIDIA driver, unsupported codec,
 * GPU decode session failure, ...) the constructor throws {@link IOException} so the caller can fall back silently.
 */
public class NvdecMagewellScrubber extends MagewellScrubber
{
   public NvdecMagewellScrubber(Camera camera, File dataDirectory, boolean hasTimeBase) throws IOException
   {
      super(camera, dataDirectory, hasTimeBase, openNvdecDemuxer(dataDirectory, camera));
   }

   private static MagewellDemuxerLike openNvdecDemuxer(File dataDirectory, Camera camera) throws IOException
   {
      File videoFile = new File(dataDirectory, camera.getVideoFileAsString());
      if (!videoFile.exists())
      {
         throw new IOException("Cannot find video: " + videoFile);
      }
      try
      {
         return new NvdecMagewellDemuxer(videoFile);
      }
      catch (FrameGrabber.Exception e)
      {
         throw new IOException("NVDEC initialization failed for " + videoFile, e);
      }
   }
}
