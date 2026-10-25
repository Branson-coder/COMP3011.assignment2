/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import javafx.scene.image.Image;

/**
 * A fully processed frame, converted to a JavaFX Image, waiting to be shown at its due time.
 * An end-of-stream marker has a null image.
 *
 * @param preparedAtNs System.nanoTime() when the pipeline finished the frame (for headroom tracing)
 */
public record DisplayFrame(int generation, int frameNumber, long timestampUs, Image image, long preparedAtNs) {

    public static DisplayFrame endOfStream(int generation) {
        return new DisplayFrame(generation, -1, 0, null, System.nanoTime());
    }

    public boolean isEndOfStream() {
        return image == null;
    }
}