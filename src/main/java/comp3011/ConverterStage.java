/*
 * Authors:

 *   Zixi Khoo A1925025
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
    private final FramePool framePool;
    private final BlockingQueue<PipelineFrame> input;
    private final BlockingQueue<DisplayFrame> output;
    private final IntSupplier currentGeneration;
    private final Consumer<Exception> errorHandler;
   

    public ConverterStage(
            Function<Frame, Image> converter,
            BlockingQueue<PipelineFrame> input,
            BlockingQueue<DisplayFrame> output,
            FramePool framePool,
            IntSupplier currentGeneration,
            Consumer<Exception> errorHandler) {
        this.converter = converter;
        this.input = input;
        this.output = output;
        this.currentGeneration = currentGeneration;
        this.errorHandler = errorHandler;
        this.framePool = framePool;
    }

    @Override
    public void run() {
        try {
            while (true) {
                PipelineFrame item = input.take();
                if (item.generation() != currentGeneration.getAsInt()) {
                    framePool.recycle(item.frame()); // stale, from before a seek
                    continue;
                }
                if (item.isEndOfStream()) {
                    output.put(DisplayFrame.endOfStream(item.generation()));
                } else {
                    Image image = converter.apply(item.frame());
                    framePool.recycle(item.frame()); // the converter has copied the pixels out
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