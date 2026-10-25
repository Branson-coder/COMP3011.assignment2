/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import org.bytedeco.javacv.Frame;

import javafx.scene.image.Image;

/**
 * Final pipeline stage: converts processed frames to JavaFX Images off the JavaFX thread.
 * The converter is only ever used from this stage's thread.
 */
public class ConverterStage implements Runnable {
    private final Function<Frame, Image> converter;
    private final BlockingQueue<PipelineFrame> input;
    private final BlockingQueue<DisplayFrame> output;
    private final IntSupplier currentGeneration;
    private final Consumer<Exception> errorHandler;

    public ConverterStage(
            Function<Frame, Image> converter,
            BlockingQueue<PipelineFrame> input,
            BlockingQueue<DisplayFrame> output,
            IntSupplier currentGeneration,
            Consumer<Exception> errorHandler) {
        this.converter = converter;
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
                    continue; // stale, from before a seek
                }
                if (item.isEndOfStream()) {
                    output.put(DisplayFrame.endOfStream(item.generation()));
                } else {
                    Image image = converter.apply(item.frame());
                    output.put(new DisplayFrame(
                            item.generation(), item.frameNumber(), item.timestampUs(), image, System.nanoTime()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // normal shutdown
        } catch (Exception | Error e) {
            errorHandler.accept(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }
}