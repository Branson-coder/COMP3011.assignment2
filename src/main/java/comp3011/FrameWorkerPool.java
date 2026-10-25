/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.concurrent.atomic.AtomicLong;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * One worker of a frame-parallel pool. Each worker owns its own complete chain of processors and
 * takes a whole frame through every effect, then converts it, so a frame stays on one core for its
 * entire journey instead of being handed between a thread per effect.
 *
 * <p>Workers finish out of order, so each worker waits for its turn before publishing: a shared
 * counter names the frame number that may be published next, and a worker holding a later frame
 * waits on the shared monitor until its turn arrives. This keeps playback linear in time.</p>
 */
public class FrameWorkerPool implements Runnable {
    private final List<FrameProcessor> chain;
    private final Function<Frame, Image> converter;
    private final BlockingQueue<PipelineFrame> input;
    private final BlockingQueue<DisplayFrame> output;
    private final FramePool framePool;
    private final IntSupplier currentGeneration;
    private final Consumer<Exception> errorHandler;
    private final Object publishLock;
    private final AtomicLong nextSequenceToPublish;

    public FrameWorkerPool(
            List<FrameProcessor> chain,
            Function<Frame, Image> converter,
            BlockingQueue<PipelineFrame> input,
            BlockingQueue<DisplayFrame> output,
            FramePool framePool,
            IntSupplier currentGeneration,
            Object publishLock,
            AtomicLong nextSequenceToPublish,
            Consumer<Exception> errorHandler) {
        this.chain = chain;
        this.converter = converter;
        this.input = input;
        this.output = output;
        this.framePool = framePool;
        this.currentGeneration = currentGeneration;
        this.publishLock = publishLock;
        this.nextSequenceToPublish = nextSequenceToPublish;
        this.errorHandler = errorHandler;
    }

    @Override
    public void run() {
        try {
            while (true) {
                PipelineFrame item = input.take();

                if (item.generation() != currentGeneration.getAsInt()) {
                    framePool.recycle(item.frame());
                    release(item.sequence());
                    continue;
                }

                DisplayFrame result;
                if (item.isEndOfStream()) {
                    result = DisplayFrame.endOfStream(item.generation());
                } else {
                    InfoFrame info = new InfoFrame(item.frameNumber(), item.timestampUs());
                    for (FrameProcessor processor : chain) {
                        processor.process(item.frame(), info);
                    }
                    Image image = converter.apply(item.frame());
                    framePool.recycle(item.frame());
                    result = new DisplayFrame(
                            item.generation(), item.frameNumber(), item.timestampUs(), image, System.nanoTime());
                }

                publishInOrder(item.sequence(), result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // normal shutdown
        } catch (Exception | Error e) {
            errorHandler.accept(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }

    // Critical region: only the worker holding the next sequence number may publish, so frames reach
    // the display queue in decode order however the workers were scheduled.
    private void publishInOrder(long sequence, DisplayFrame result) throws InterruptedException {
        synchronized (publishLock) {
            while (nextSequenceToPublish.get() != sequence) {
                publishLock.wait();
            }
        }

        output.put(result);
        release(sequence);
    }

    private void release(long sequence) {
        synchronized (publishLock) {
            if (nextSequenceToPublish.get() == sequence) {
                nextSequenceToPublish.incrementAndGet();
                publishLock.notifyAll();
            }
        }
    }
}