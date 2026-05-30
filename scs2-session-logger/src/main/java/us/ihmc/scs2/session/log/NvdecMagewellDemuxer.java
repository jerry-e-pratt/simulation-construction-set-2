package us.ihmc.scs2.session.log;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import us.ihmc.log.LogTools;

import java.io.File;

/**
 * NVDEC-accelerated counterpart to the upstream software {@code MagewellDemuxer}. Picks the matching
 * {@code *_cuvid} decoder for the source's video codec (HEVC, H.264, VP9, ...) and configures the
 * {@link FFmpegFrameGrabber} to run decode on the GPU before {@code start()}.
 * <p>
 * Constructor throws when NVDEC is unavailable (no NVIDIA driver, codec unsupported by CUVID, GPU
 * decode session creation fails, ...); callers should catch and fall back to the software path.
 */
public final class NvdecMagewellDemuxer implements MagewellDemuxerLike
{
   private static final String NAME = "NVDEC MageWell Demuxer";

   private final FFmpegFrameGrabber grabber;

   /**
    * Minimum post-resize width/height. Below this we skip the 2:1 GPU downsample so we don't ask
    * CUVID to produce postage stamps from already-small captures.
    */
   private static final int MIN_RESIZE_DIM = 64;

   public NvdecMagewellDemuxer(File videoFile) throws FrameGrabber.Exception
   {
      ProbeResult probe = probeSource(videoFile);

      FFmpegFrameGrabber nvdecGrabber = new FFmpegFrameGrabber(videoFile);
      nvdecGrabber.setVideoCodecName(probe.cuvidName);

      String resize = computeHalfResize(probe.width, probe.height);
      if (resize != null)
      {
         // CUVID applies the resize inside the GPU decoder before the frame is mapped to host memory,
         // so the readback + swscale + getPixels stages downstream all run on 1/4 the pixels.
         nvdecGrabber.setVideoOption("resize", resize);
         LogTools.info("NVDEC 2:1 GPU resize enabled: %dx%d -> %s (%s)".formatted(probe.width, probe.height, resize, probe.cuvidName));
      }

      try
      {
         nvdecGrabber.start();
      }
      catch (FrameGrabber.Exception startFailure)
      {
         try
         {
            nvdecGrabber.release();
         }
         catch (FrameGrabber.Exception releaseFailure)
         {
            startFailure.addSuppressed(releaseFailure);
         }
         throw startFailure;
      }
      grabber = nvdecGrabber;
   }

   /**
    * Returns a {@code "WxH"} option string for the CUVID {@code resize} private option, halving each
    * source dimension and rounding down to the nearest even integer (CUVID requires even dims). Returns
    * {@code null} when the source is already small enough that 2:1 would produce a degenerate frame.
    */
   private static String computeHalfResize(int srcWidth, int srcHeight)
   {
      if (srcWidth <= 0 || srcHeight <= 0)
         return null;
      int w = (srcWidth / 2) & ~1;
      int h = (srcHeight / 2) & ~1;
      if (w < MIN_RESIZE_DIM || h < MIN_RESIZE_DIM)
         return null;
      return w + "x" + h;
   }

   private record ProbeResult(String cuvidName, int width, int height) {}

   /**
    * Opens the file briefly with the default (auto-detected) decoder, reads the stream's codec ID and
    * source resolution, and picks the matching CUVID decoder name. Throws when the stream's codec
    * isn't covered by NVDEC.
    */
   private static ProbeResult probeSource(File videoFile) throws FrameGrabber.Exception
   {
      FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoFile);
      try
      {
         probe.start();
         int codecId = probe.getVideoCodec();
         String cuvidName = cuvidNameFor(codecId);
         if (cuvidName == null)
            throw new FrameGrabber.Exception("No CUVID decoder for codec id " + codecId);
         return new ProbeResult(cuvidName, probe.getImageWidth(), probe.getImageHeight());
      }
      finally
      {
         try
         {
            probe.stop();
         }
         catch (FrameGrabber.Exception ignored)
         {
         }
         try
         {
            probe.release();
         }
         catch (FrameGrabber.Exception ignored)
         {
         }
      }
   }

   private static String cuvidNameFor(int codecId)
   {
      if (codecId == avcodec.AV_CODEC_ID_HEVC)
         return "hevc_cuvid";
      if (codecId == avcodec.AV_CODEC_ID_H264)
         return "h264_cuvid";
      if (codecId == avcodec.AV_CODEC_ID_VP9)
         return "vp9_cuvid";
      if (codecId == avcodec.AV_CODEC_ID_VP8)
         return "vp8_cuvid";
      if (codecId == avcodec.AV_CODEC_ID_AV1)
         return "av1_cuvid";
      if (codecId == avcodec.AV_CODEC_ID_MPEG2VIDEO)
         return "mpeg2_cuvid";
      return null;
   }

   @Override
   public String getName()
   {
      return NAME;
   }

   @Override
   public int getImageHeight()
   {
      return grabber.getImageHeight();
   }

   @Override
   public int getImageWidth()
   {
      return grabber.getImageWidth();
   }

   @Override
   public long getCurrentPTS()
   {
      return grabber.getTimestamp();
   }

   @Override
   public void seekToPTS(long videoTimestamp)
   {
      try
      {
         grabber.setTimestamp(videoTimestamp);
      }
      catch (FFmpegFrameGrabber.Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   @Override
   public int getFrameNumber()
   {
      return grabber.getFrameNumber();
   }

   @Override
   public Frame getNextFrame()
   {
      try
      {
         return grabber.grabFrame();
      }
      catch (FrameGrabber.Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   @Override
   public double getFrameRate()
   {
      return grabber.getVideoFrameRate();
   }

   @Override
   public void stop()
   {
      try
      {
         grabber.stop();
      }
      catch (FFmpegFrameGrabber.Exception e)
      {
         LogTools.error(e.getMessage());
      }
   }
}
