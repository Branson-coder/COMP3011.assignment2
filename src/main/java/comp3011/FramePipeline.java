/*
 * Authors:
 *  Zixi Khoo A1925025
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Owns the threads and queues of one playback session, as a frame-parallel pool:
 *
 * <pre>
 * decoder -&gt; [queue] -&gt; worker 1..N (each runs a whole frame through every effect) -&gt; [display queue]
 * </pre>
 *
 * <p>Each worker has its own chain of processor instances, so no processor is ever touched by two
 * threads, and a frame stays on one core for its whole journey instead of being handed between a
 * thread per effect. Thread count is set by the number of cores rather than the number of effects.</p>
 *
 * <p>Workers finish out of order, so publishing is serialised by sequence number (see
 * {@link FrameWorkerPool}), keeping playback linear in time. Seeks use a generation counter: every
 * stage discards frames whose generation is not the current one.</p>
 */
public class FramePipeline implements AutoCloseable {
    private static final int INPUT_QUEUE_CAPACITY = 8;
    private static final int DISPLAY_QUEUE_CAPACITY = 400;
    private static final long JOIN_TIMEOUT_MS = 3_000;

    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong nextSequenceToPublish = new AtomicLong();
    private final Object publishLock = new Object();
    private final List<BlockingQueue<?>> queues = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final BlockingQueue<DisplayFrame> displayQueue = new ArrayBlockingQueue<>(DISPLAY_QUEUE_CAPACITY);
    private final FramePool framePool;
    private final List<List<FrameProcessor>> chains = new ArrayList<>();
    private final DecoderTask decoder;
    private volatile boolean closing;

    public FramePipeline(
            FFmpegFrameGrabber grabber,
            boolean audioAvailable,
            List<FrameProcessorOption> processorOptions,
            Function<Frame, Image> converter,
            DecoderTask.Callbacks callbacks,
            Consumer<Exception> errorHandler) {
        Consumer<Exception> guardedErrorHandler = e -> {
            if (!closing) {
                errorHandler.accept(e);
            }
        };

        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        framePool = new FramePool(workerCount * 2 + INPUT_QUEUE_CAPACITY + DISPLAY_QUEUE_CAPACITY);

        BlockingQueue<PipelineFrame> inputQueue = new ArrayBlockingQueue<>(INPUT_QUEUE_CAPACITY);
        queues.add(inputQueue);
        queues.add(displayQueue);

        decoder = new DecoderTask(
                grabber, audioAvailable, generation.get(), inputQueue, framePool, callbacks, guardedErrorHandler);
        addThread("decoder", decoder);

        for (int worker = 1; worker <= workerCount; worker++) {
            List<FrameProcessor> chain = FrameProcessorOption.createChain(processorOptions);
            chains.add(chain);
            addThread("worker-" + worker, new FrameWorkerPool(
                    chain,
                    converter,
                    inputQueue,
                    displayQueue,
                    framePool,
                    generation::get,
                    publishLock,
                    nextSequenceToPublish,
                    guardedErrorHandler));
        }
    }

    public void start() {
        threads.forEach(Thread::start);
    }
    
    public void initialiseProcessors(InfoVideo videoInfo) throws Exception {
        for (List<FrameProcessor> chain : chains) {
            for (FrameProcessor processor : chain) {
                processor.initialise(videoInfo);
            }
        }
    }
    
    public int currentGeneration() {
        return generation.get();
    }
    public int displayQueueSize() {
        return displayQueue.size();
    }

    /** Non-blocking. Only the JavaFX thread consumes, so peek then poll is safe. */
    public DisplayFrame peekDisplayFrame() {
        return displayQueue.peek();
    }

    public DisplayFrame pollDisplayFrame() {
        return displayQueue.poll();
    }

    /**
     * Moves playback to a new position. The sequence counter is reset and every waiting worker woken,
     * so no worker is left waiting for a sequence number that the decoder will never issue again.
     */
    public void seek(long timestampUs) {
        int newGeneration = generation.incrementAndGet();
        queues.forEach(BlockingQueue::clear);
        synchronized (publishLock) {
            nextSequenceToPublish.set(0);
            publishLock.notifyAll();
        }
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
        framePool.clear();
    }

    private void addThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true); // never keep the JVM alive
        threads.add(thread);
    }
}