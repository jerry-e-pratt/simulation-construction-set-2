package us.ihmc.scs2.sessionVisualizer.jfx.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.ihmc.scs2.sessionVisualizer.jfx.managers.BackgroundExecutorManager;

public class LatestTimestampBackgroundExecutorTest
{
   private BackgroundExecutorManager executor;

   @BeforeEach
   public void setUp()
   {
      executor = new BackgroundExecutorManager(2);
   }

   @AfterEach
   public void tearDown()
   {
      executor.shutdown();
   }

   @Test
   public void testLatestTimestampIsServiced() throws InterruptedException
   {
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch firstStarted = new CountDownLatch(1);
      List<Long> serviced = new ArrayList<>();
      Object servicedLock = new Object();
      AtomicInteger invocations = new AtomicInteger(0);

      LatestTimestampBackgroundExecutor coalescer = new LatestTimestampBackgroundExecutor(executor, ts ->
      {
         if (invocations.getAndIncrement() == 0)
         {
            firstStarted.countDown();
            try
            {
               releaseFirst.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
               Thread.currentThread().interrupt();
            }
         }
         synchronized (servicedLock)
         {
            serviced.add(ts);
         }
      });

      coalescer.submit(1L);
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "First invocation never started");

      for (long i = 2; i <= 100; i++)
         coalescer.submit(i);

      releaseFirst.countDown();

      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline)
      {
         synchronized (servicedLock)
         {
            if (!serviced.isEmpty() && serviced.get(serviced.size() - 1) == 100L)
               break;
         }
         Thread.sleep(10);
      }

      synchronized (servicedLock)
      {
         assertFalse(serviced.isEmpty(), "Worker was never invoked");
         assertEquals(1L, serviced.get(0).longValue(), "First invocation should have received timestamp 1");
         assertEquals(100L, serviced.get(serviced.size() - 1).longValue(), "Latest timestamp 100 must eventually be serviced");
         assertTrue(serviced.size() < 100, "Expected coalescing to drop intermediate timestamps; got " + serviced.size() + " invocations");
      }
   }

   @Test
   public void testSingleSubmitInvokesOnce() throws InterruptedException
   {
      CountDownLatch done = new CountDownLatch(1);
      List<Long> serviced = new ArrayList<>();
      Object servicedLock = new Object();

      LatestTimestampBackgroundExecutor coalescer = new LatestTimestampBackgroundExecutor(executor, ts ->
      {
         synchronized (servicedLock)
         {
            serviced.add(ts);
         }
         done.countDown();
      });

      coalescer.submit(42L);
      assertTrue(done.await(2, TimeUnit.SECONDS));
      Thread.sleep(50);

      synchronized (servicedLock)
      {
         assertEquals(1, serviced.size());
         assertEquals(42L, serviced.get(0).longValue());
      }
   }

   @Test
   public void testSubmitAfterDrainStartsFreshTask() throws InterruptedException
   {
      List<Long> serviced = new ArrayList<>();
      Object servicedLock = new Object();
      LatestTimestampBackgroundExecutor coalescer = new LatestTimestampBackgroundExecutor(executor, ts ->
      {
         synchronized (servicedLock)
         {
            serviced.add(ts);
         }
      });

      for (int i = 0; i < 10; i++)
      {
         coalescer.submit(i);
         Thread.sleep(20);
      }

      long deadline = System.currentTimeMillis() + 2000;
      while (System.currentTimeMillis() < deadline)
      {
         synchronized (servicedLock)
         {
            if (!serviced.isEmpty() && serviced.get(serviced.size() - 1) == 9L)
               break;
         }
         Thread.sleep(10);
      }

      synchronized (servicedLock)
      {
         assertEquals(9L, serviced.get(serviced.size() - 1).longValue(), "Final timestamp 9 must be serviced");
      }
   }
}
