/*
 * Authors:
 *  Zixi Khoo A1925025
 */
package comp3011;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.bytedeco.javacv.Frame;

/**
 * A fixed set of reusable frame buffers, so the decoder does not allocate a new 6MB frame for
 * every frame of video. The decoder takes a buffer, the frame travels the pipeline, and the
 * converter hands the buffer back once the pixels have been copied into a JavaFX Image.
 *
 * <p>Thread safe by construction: a buffer is owned by exactly one thread at a time, and
 * ownership moves through the pipeline queues. A buffer is only ever recycled after the last
 * stage has finished reading it, so no stage can be working on a frame that is handed out again.
 * If the pool is empty a new buffer is allocated, and if it is full a returned buffer is simply
 * dropped, so the pool can never stall the pipeline.</p>
 */
public final class FramePool {
    private final BlockingQueue<Frame> freeFrames;

    public FramePool(int capacity) {
        freeFrames = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    /** Returns a frame holding a copy of source's pixels, reusing a pooled buffer when possible. */
    public Frame copyOf(Frame source) {
        Frame buffer = freeFrames.poll();
        if (buffer != null && copyImageInto(source, buffer)) {
            return buffer;
        }

        Frame allocated = source.clone();
        allocated.samples = null; // the pipeline only ever reads the image
        return allocated;
    }

    /** Hands a buffer back for reuse. Only called once the frame's pixels are no longer needed. */
    public void recycle(Frame frame) {
        if (frame != null) {
            freeFrames.offer(frame);
        }
    }

    public void clear() {
        freeFrames.clear();
    }

    // Only reuses a buffer whose geometry matches exactly; otherwise the caller allocates.
    private boolean copyImageInto(Frame source, Frame buffer) {
        if (source.image == null || source.image.length != 1
                || buffer.image == null || buffer.image.length != 1) {
            return false;
        }
        if (!(source.image[0] instanceof ByteBuffer sourcePixels)
                || !(buffer.image[0] instanceof ByteBuffer bufferPixels)) {
            return false;
        }
        if (sourcePixels.capacity() != bufferPixels.capacity()
                || source.imageWidth != buffer.imageWidth
                || source.imageHeight != buffer.imageHeight
                || source.imageStride != buffer.imageStride
                || source.imageChannels != buffer.imageChannels) {
            return false;
        }

        ByteBuffer from = sourcePixels.duplicate();
        ByteBuffer to = bufferPixels.duplicate();
        from.position(0).limit(from.capacity());
        to.position(0).limit(to.capacity());
        to.put(from);
        return true;
    }
}