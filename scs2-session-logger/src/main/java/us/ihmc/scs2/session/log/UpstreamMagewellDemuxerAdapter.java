package us.ihmc.scs2.session.log;

import org.bytedeco.javacv.Frame;
import us.ihmc.robotDataLogger.logger.MagewellDemuxer;

/**
 * Adapts the upstream {@link MagewellDemuxer} to {@link MagewellDemuxerLike}, preserving the existing
 * software-decode behavior used by {@link MagewellScrubber}'s default constructor.
 */
final class UpstreamMagewellDemuxerAdapter implements MagewellDemuxerLike
{
   private final MagewellDemuxer delegate;

   UpstreamMagewellDemuxerAdapter(MagewellDemuxer delegate)
   {
      this.delegate = delegate;
   }

   @Override
   public String getName()
   {
      return delegate.getName();
   }

   @Override
   public int getImageHeight()
   {
      return delegate.getImageHeight();
   }

   @Override
   public int getImageWidth()
   {
      return delegate.getImageWidth();
   }

   @Override
   public long getCurrentPTS()
   {
      return delegate.getCurrentPTS();
   }

   @Override
   public void seekToPTS(long videoTimestamp)
   {
      delegate.seekToPTS(videoTimestamp);
   }

   @Override
   public int getFrameNumber()
   {
      return delegate.getFrameNumber();
   }

   @Override
   public Frame getNextFrame()
   {
      return delegate.getNextFrame();
   }

   @Override
   public double getFrameRate()
   {
      return delegate.getFrameRate();
   }

   @Override
   public void stop()
   {
      delegate.stop();
   }
}
