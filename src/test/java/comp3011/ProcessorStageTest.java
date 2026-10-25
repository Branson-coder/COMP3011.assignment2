/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class ProcessorStageTest {
    private final BlockingQueue<PipelineFrame> input = new ArrayBlockingQueue<>(8);
    private final BlockingQueue<PipelineFrame> output = new ArrayBlockingQueue<>(8);
    private final AtomicInteger generation = new AtomicInteger(1);
    private final List<Integer> seen = new CopyOnWriteArrayList<>();

    private final FrameProcessor recorder = new FrameProcessor() {
        @Override
        public void process(Frame frame, InfoFrame info) {
            seen.add(info.frameNumber());
        }
    };

    private Thread startStage() {
        Thread thread = new Thread(new ProcessorStage(
                recorder, input, output, generation::get, e -> fail("stage failed: " + e)));
        thread.start();
        return thread;
    }

    private PipelineFrame next() throws InterruptedException {
        PipelineFrame item = output.poll(2, TimeUnit.SECONDS);
        assertNotNull(item, "timed out waiting for stage output");
        return item;
    }

    private void stop(Thread thread) throws InterruptedException {
        thread.interrupt();
        thread.join(2000);
        assertFalse(thread.isAlive());
    }

    @Test
    void processesFramesInOrderAndForwardsEndMarker() throws Exception {
        Thread thread = startStage();
        input.put(new PipelineFrame(1, 0, 0, new Frame()));
        input.put(new PipelineFrame(1, 1, 1000, new Frame()));
        input.put(new PipelineFrame(1, 2, 2000, new Frame()));
        input.put(PipelineFrame.endOfStream(1));

        assertEquals(0, next().frameNumber());
        assertEquals(1, next().frameNumber());
        assertEquals(2, next().frameNumber());
        assertTrue(next().isEndOfStream());
        assertEquals(List.of(0, 1, 2), seen);
        stop(thread);
    }

    @Test
    void dropsFramesFromStaleGenerations() throws Exception {
        Thread thread = startStage();
        input.put(new PipelineFrame(0, 5, 0, new Frame())); // old generation
        input.put(new PipelineFrame(1, 6, 0, new Frame()));
        input.put(PipelineFrame.endOfStream(1));

        assertEquals(6, next().frameNumber());
        assertTrue(next().isEndOfStream());
        assertEquals(List.of(6), seen);
        stop(thread);
    }

    @Test
    void interruptStopsAStageBlockedOnEmptyInput() throws Exception {
        Thread thread = startStage();
        stop(thread);
    }
}