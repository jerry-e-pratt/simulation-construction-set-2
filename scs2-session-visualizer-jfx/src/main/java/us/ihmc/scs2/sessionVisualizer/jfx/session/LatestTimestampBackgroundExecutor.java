package us.ihmc.scs2.sessionVisualizer.jfx.session;

import java.util.concurrent.Future;
import java.util.function.LongConsumer;

import us.ihmc.scs2.sessionVisualizer.jfx.managers.BackgroundExecutorManager;

/**
 * Funnels timestamp updates to a background worker with latest-wins coalescing.
 * <p>
 * When {@link #submit(long)} is called while a previous worker invocation is still running, the running invocation is left alone and the new timestamp is
 * latched. Once the running invocation returns, the worker is automatically re-invoked with the most recently latched timestamp. This guarantees the latest
 * submitted timestamp is eventually serviced while bounding executor pressure to at most one running invocation plus one pending timestamp.
 * </p>
 */
public class LatestTimestampBackgroundExecutor
{
   private final BackgroundExecutorManager backgroundExecutorManager;
   private final LongConsumer worker;

   private final Object lock = new Object();
   private long pendingTimestamp;
   private boolean hasPending = false;
   private Future<?> currentTask = null;

   public LatestTimestampBackgroundExecutor(BackgroundExecutorManager backgroundExecutorManager, LongConsumer worker)
   {
      this.backgroundExecutorManager = backgroundExecutorManager;
      this.worker = worker;
   }

   public void submit(long timestamp)
   {
      synchronized (lock)
      {
         pendingTimestamp = timestamp;
         hasPending = true;
         if (currentTask == null)
            currentTask = backgroundExecutorManager.executeInBackground(this::drain);
      }
   }

   private void drain()
   {
      while (true)
      {
         long ts;
         synchronized (lock)
         {
            if (!hasPending)
            {
               currentTask = null;
               return;
            }
            hasPending = false;
            ts = pendingTimestamp;
         }
         try
         {
            worker.accept(ts);
         }
         catch (Throwable t)
         {
            t.printStackTrace();
         }
      }
   }
}
