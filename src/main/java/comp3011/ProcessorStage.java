/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Pipeline stage that applies a single {@link FrameProcessor} to every frame it receives,
 * in arrival order, then passes the frame on. Runs on its own thread; the processor is only
 * ever used from that thread, so processors need no locking.
 *
 * <p>Queues are bounded, so put() blocks when the next stage is slow (back-pressure), and
 * take()/put() respond to interrupt, which is how the pipeline is shut down.</p>
 */
public class ProcessorStage implements Runnable {
    private final FrameProcessor processor;
    private final BlockingQueue<PipelineFrame> input;
    private final BlockingQueue<PipelineFrame> output;
    private final IntSupplier currentGeneration;
    private final Consumer<Exception> errorHandler;

    public ProcessorStage(
            FrameProcessor processor,
            BlockingQueue<PipelineFrame> input,
            BlockingQueue<PipelineFrame> output,
            IntSupplier currentGeneration,
            Consumer<Exception> errorHandler) {
        this.processor = processor;
        this.input = input;
        this.output = output;
        this.currentGeneration = currentGeneration;
        this.errorHandler = errorHandler;
    }

    @Override
    public void run() {
        try {
            while (true) {
                PipelineFrame item = input.take();
                if (item.generation() != currentGeneration.getAsInt()) {
                    continue; // left over from before a seek: drop it
                }
                if (!item.isEndOfStream()) {
                    processor.process(item.frame(), new InfoFrame(item.frameNumber(), item.timestampUs()));
                }
                output.put(item);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // normal shutdown
        } catch (Exception | Error e) {
            errorHandler.accept(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }
}