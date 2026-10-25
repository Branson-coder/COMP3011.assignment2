/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Owns the threads and queues of one playback session:
 *
 * <pre>
 * decoder -> [queue] -> effect 1 -> [queue] -> ... -> effect N -> [queue] -> converter -> [display queue]
 * </pre>
 *
 * <p>The JavaFX thread is the only consumer of the display queue and never blocks on it
 * (peek/poll only). There are no locks anywhere: frames are handed between threads through
 * bounded BlockingQueues, which provide both safe publication and back-pressure, and the
 * pipeline is acyclic, so it cannot deadlock. Seeks are handled with a generation counter:
 * every stage discards frames whose generation is not the current one.</p>
 */
public class FramePipeline implements AutoCloseable {
    private static final int STAGE_QUEUE_CAPACITY = 2;
    private static final int DISPLAY_QUEUE_CAPACITY = 4;
    private static final long JOIN_TIMEOUT_MS = 3_000;

    private final AtomicInteger generation = new AtomicInteger();
    private final List<BlockingQueue<?>> queues = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final BlockingQueue<DisplayFrame> displayQueue = new ArrayBlockingQueue<>(DISPLAY_QUEUE_CAPACITY);
    private final DecoderTask decoder;
    private volatile boolean closing;

    public FramePipeline(
            FFmpegFrameGrabber grabber,
            boolean audioAvailable,
            List<FrameProcessor> processors,
            Function<Frame, Image> converter,
            DecoderTask.Callbacks callbacks,
            Consumer<Exception> errorHandler) {
        // Errors raised while shutting down (interrupts etc.) are expected and not reported.
        Consumer<Exception> guardedErrorHandler = e -> {
            if (!closing) {
                errorHandler.accept(e);
            }
        };

        BlockingQueue<PipelineFrame> upstream = newStageQueue();
        decoder = new DecoderTask(grabber, audioAvailable, generation.get(), upstream, callbacks, guardedErrorHandler);
        addThread("decoder", decoder);

        int index = 1;
        for (FrameProcessor processor : processors) {
            BlockingQueue<PipelineFrame> downstream = newStageQueue();
            addThread("effect-" + index++ + "-" + processor.getClass().getSimpleName(),
                    new ProcessorStage(processor, upstream, downstream, generation::get, guardedErrorHandler));
            upstream = downstream;
        }

        queues.add(displayQueue);
        addThread("converter",
                new ConverterStage(converter, upstream, displayQueue, generation::get, guardedErrorHandler));
    }

    public void start() {
        threads.forEach(Thread::start);
    }

    public int currentGeneration() {
        return generation.get();
    }

    /** Non-blocking. Only the JavaFX thread consumes, so peek then poll is safe. */
    public DisplayFrame peekDisplayFrame() {
        return displayQueue.peek();
    }

    public DisplayFrame pollDisplayFrame() {
        return displayQueue.poll();
    }

    /**
     * Moves playback to a new position. Order matters: bump the generation first (so anything still
     * in flight is recognised as stale), then empty the queues, and only then ask the decoder to
     * seek (so no frame from the new position can be thrown away by the clear).
     */
    public void seek(long timestampUs) {
        int newGeneration = generation.incrementAndGet();
        queues.forEach(BlockingQueue::clear);
        decoder.requestSeek(newGeneration, timestampUs);
    }

    @Override
    public void close() {
        closing = true;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        queues.forEach(BlockingQueue::clear);
    }

    private BlockingQueue<PipelineFrame> newStageQueue() {
        BlockingQueue<PipelineFrame> queue = new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY);
        queues.add(queue);
        return queue;
    }

    private void addThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true); // never keep the JVM alive
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        threads.add(thread);
    }
}