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

   public NvdecMagewellDemuxer(File videoFile) throws FrameGrabber.Exception
   {
      String cuvidName = pickCuvidDecoder(videoFile);

      FFmpegFrameGrabber nvdecGrabber = new FFmpegFrameGrabber(videoFile);
      nvdecGrabber.setVideoCodecName(cuvidName);
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
    * Opens the file briefly with the default (auto-detected) decoder, reads the stream's codec ID, and
    * picks the matching CUVID decoder name. Throws when the stream's codec isn't covered by NVDEC.
    */
   private static String pickCuvidDecoder(File videoFile) throws FrameGrabber.Exception
   {
      FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoFile);
      try
      {
         probe.start();
         int codecId = probe.getVideoCodec();
         String cuvidName = cuvidNameFor(codecId);
         if (cuvidName == null)
            throw new FrameGrabber.Exception("No CUVID decoder for codec id " + codecId);
         return cuvidName;
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
