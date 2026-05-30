package us.ihmc.scs2.session.log;

import org.bytedeco.javacv.Frame;

/**
 * Surface that {@link MagewellScrubber} and friends need from a demuxer. Lets the scrubber compose the
 * upstream software {@code MagewellDemuxer} or an NVDEC-backed alternative ({@link NvdecMagewellDemuxer})
 * without committing to either concrete type.
 */
public interface MagewellDemuxerLike
{
   String getName();

   int getImageHeight();

   int getImageWidth();

   long getCurrentPTS();

   void seekToPTS(long videoTimestamp);

   int getFrameNumber();

   Frame getNextFrame();

   double getFrameRate();

   void stop();
}
