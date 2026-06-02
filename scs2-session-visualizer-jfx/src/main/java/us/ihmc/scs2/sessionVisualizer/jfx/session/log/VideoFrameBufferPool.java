package us.ihmc.scs2.sessionVisualizer.jfx.session.log;

import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted pool of {@link FrameBuffer} instances backing video frames decoded by the Magewell readers.
 * <p>
 * Reusing a single {@link PixelBuffer} per ConcurrentCopier slot is unsafe because the JavaFX render pulse uploads the
 * slot's pixels asynchronously: the writer thread can mutate the backing {@link ByteBuffer} while the render thread is
 * still reading it, producing torn frames that surface to the user as "jumps to previous frames." This pool decouples
 * the buffer lifetime from the slot lifetime: the writer transfers its single reference to the slot on commit, and the
 * JavaFX consumer takes an extra reference for the duration it needs the pixels to remain stable. A buffer is recycled
 * only once every holder has released it.
 * <p>
 * The pool is thread-safe. {@link #acquire(int, int)} runs on the decode thread; {@link FrameBuffer#release()} can be
 * called from the decode thread (when the writer reuses a slot and drops its previous buffer) or the JavaFX Application
 * Thread (when the consumer drops a previously displayed buffer). When the requested dimensions differ from the pool's
 * active dimensions, free buffers of the stale size are dropped so direct memory can be reclaimed.
 */
public class VideoFrameBufferPool
{
   /**
    * Default cap for the free list. Sized for the steady state of one Magewell stream: 3 ConcurrentCopier slots + 2 FX
    * holds (current frame and the previous frame still in the render pulse) + 1 headroom for scheduling gaps.
    */
   public static final int DEFAULT_MAX_POOL_SIZE = 6;
   // PixelBuffer requires premultiplied alpha; video frames are opaque so this is a no-op vs. non-premultiplied.
   private static final WritablePixelFormat<IntBuffer> ARGB_PRE_PIXEL_FORMAT = PixelFormat.getIntArgbPreInstance();

   private final int maxPoolSize;
   private final Deque<FrameBuffer> freeList = new ConcurrentLinkedDeque<>();
   // Active dimensions guard. Buffers that don't match are not returned to the free list; the active dimensions are
   // updated atomically when an acquire arrives with a new size, and any stale free buffers are dropped on the spot.
   private volatile int activeWidth = 0;
   private volatile int activeHeight = 0;

   public VideoFrameBufferPool()
   {
      this(DEFAULT_MAX_POOL_SIZE);
   }

   public VideoFrameBufferPool(int maxPoolSize)
   {
      this.maxPoolSize = maxPoolSize;
   }

   /**
    * Obtain a buffer of the requested dimensions with its reference count set to 1. A free buffer of the matching size
    * is recycled when available, otherwise a fresh one is allocated. Changing dimensions discards stale free buffers.
    */
   public synchronized FrameBuffer acquire(int width, int height)
   {
      if (width != activeWidth || height != activeHeight)
      {
         freeList.clear();
         activeWidth = width;
         activeHeight = height;
      }
      FrameBuffer buffer = pollFreeOfSize(width, height);
      if (buffer == null)
         buffer = new FrameBuffer(width, height, this);
      buffer.refCount.set(1);
      return buffer;
   }

   private FrameBuffer pollFreeOfSize(int width, int height)
   {
      Iterator<FrameBuffer> iterator = freeList.iterator();
      while (iterator.hasNext())
      {
         FrameBuffer candidate = iterator.next();
         if (candidate.width == width && candidate.height == height)
         {
            iterator.remove();
            return candidate;
         }
      }
      return null;
   }

   void recycle(FrameBuffer buffer)
   {
      if (buffer.width != activeWidth || buffer.height != activeHeight)
         return;
      if (freeList.size() >= maxPoolSize)
         return;
      freeList.offerFirst(buffer);
   }

   public int getMaxPoolSize()
   {
      return maxPoolSize;
   }

   public int getFreeCount()
   {
      return freeList.size();
   }

   /**
    * One reference-counted unit of pixel storage: a direct {@link ByteBuffer}, a {@link PixelBuffer} aliasing that
    * buffer as IntArgbPre, and the {@link WritableImage} wrapping the PixelBuffer for the FX scene graph. Producers and
    * consumers each hold a reference for as long as they may touch the pixels; the underlying memory is recycled only
    * once every holder has released.
    */
   public static final class FrameBuffer
   {
      public final int width;
      public final int height;
      public final ByteBuffer pixelByteBuffer;
      public final PixelBuffer<IntBuffer> pixelBuffer;
      public final WritableImage image;
      final AtomicInteger refCount = new AtomicInteger(0);
      private final VideoFrameBufferPool owner;

      FrameBuffer(int width, int height, VideoFrameBufferPool owner)
      {
         this.width = width;
         this.height = height;
         this.owner = owner;
         this.pixelByteBuffer = ByteBuffer.allocateDirect(width * height * Integer.BYTES).order(ByteOrder.nativeOrder());
         this.pixelBuffer = new PixelBuffer<>(width, height, pixelByteBuffer.asIntBuffer(), ARGB_PRE_PIXEL_FORMAT);
         this.image = new WritableImage(pixelBuffer);
      }

      public void retain()
      {
         refCount.incrementAndGet();
      }

      public void release()
      {
         int after = refCount.decrementAndGet();
         if (after < 0)
            throw new IllegalStateException("FrameBuffer released past zero (refCount=" + after + ")");
         if (after == 0)
            owner.recycle(this);
      }

      public int getRefCount()
      {
         return refCount.get();
      }
   }
}
