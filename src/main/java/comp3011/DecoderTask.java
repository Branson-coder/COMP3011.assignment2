/*
 * Authors:
 *   Zixi Khoo A1925025
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Pipeline source. Owns the FFmpeg grabber (which is not thread-safe) and is the only
 * thread that ever calls it. Decodes frames, hands audio to the callbacks, and puts a
 * deep copy of each video frame on the output queue (the grabber reuses its own buffers,
 * so a frame must be cloned before another thread can safely work on it).
 *
 * <p>Seeks arrive from other threads as requests. The decoder only adopts a new generation
 * number at the moment it actually performs the seek, so every frame tagged with generation
 * N really did come from the position requested by seek N.</p>
 */
public class DecoderTask implements Runnable {

    /** Called on the decoder thread. */
    public interface Callbacks {
        /** Once per playback, with the first image frame, before it is queued. */
        void firstImageFrame(Frame frame, int pixelFormat) throws Exception;

        void audioDecoded(int generation, long timestampUs, Frame frame);
    }

    private record SeekRequest(int generation, long timestampUs) {
    }

    private final FFmpegFrameGrabber grabber;
    private final boolean audioAvailable;
    private final BlockingQueue<PipelineFrame> output;
    private final FramePool framePool;
    private final Callbacks callbacks;
    private final Consumer<Exception> errorHandler;
    private final BlockingQueue<SeekRequest> seekRequests = new LinkedBlockingQueue<>();

    private int generation; // decoder thread only
    private boolean initialised; // decoder thread only

    public DecoderTask(
            FFmpegFrameGrabber grabber,
            boolean audioAvailable,
            int initialGeneration,
            BlockingQueue<PipelineFrame> output,
            FramePool framePool,
            Callbacks callbacks,
            Consumer<Exception> errorHandler) {
        this.grabber = grabber;
        this.audioAvailable = audioAvailable;
        this.generation = initialGeneration;
        this.output = output;
        this.framePool = framePool;
        this.callbacks = callbacks;
        this.errorHandler = errorHandler;
    }

    /** Safe to call from any thread. */
    public void requestSeek(int newGeneration, long timestampUs) {
        seekRequests.add(new SeekRequest(newGeneration, timestampUs));
    }

    @Override
    public void run() {
        try {
            while (true) {
                applySeeks(false);

                Frame frame = audioAvailable ? grabber.grab() : grabber.grabImage();
                if (frame == null) {
                    output.put(PipelineFrame.endOfStream(generation));
                    applySeeks(true); // keep the thread alive: the user may still seek back
                    continue;
                }

                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    callbacks.audioDecoded(generation, timestampUs, frame);
                }
                if (frame.image == null) {
                    continue;
                }
                if (!initialised) {
                    callbacks.firstImageFrame(frame, grabber.getPixelFormat());
                    initialised = true;
                }
                output.put(new PipelineFrame(
                        generation, grabber.getFrameNumber(), timestampUs, framePool.copyOf(frame)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // normal shutdown
        } catch (Exception | Error e) {
            errorHandler.accept(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }

    // Uses only the most recent request if several piled up (e.g. the user tapped RIGHT repeatedly).
    private void applySeeks(boolean wait) throws Exception {
        SeekRequest latest = wait ? seekRequests.take() : seekRequests.poll();
        SeekRequest next;
        while ((next = seekRequests.poll()) != null) {
            latest = next;
        }
        if (latest != null) {
            grabber.setTimestamp(latest.timestampUs());
            generation = latest.generation();
        }
    }
}