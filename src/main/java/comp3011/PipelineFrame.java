/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import org.bytedeco.javacv.Frame;

/**
 * A decoded video frame travelling through the pipeline. Owned by exactly one stage
 * at a time; ownership is handed over by putting it on a BlockingQueue.
 *
 * @param generation increments on every seek/restart so stale frames can be dropped
 * @param frame      the pixels, or null for the end-of-stream marker
 */
public record PipelineFrame(int generation, long sequence, int frameNumber, long timestampUs, Frame frame) {

    public static PipelineFrame endOfStream(int generation, long sequence) {
        return new PipelineFrame(generation, sequence, -1, 0, null);
    }

    public boolean isEndOfStream() {
        return frame == null;
    }
}