/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <your name and student number>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns playback state, the playback clock, audio scheduling and the {@link FramePipeline} that does
 * the decoding, effect processing and image conversion on background threads. The model itself only
 * ever runs on the JavaFX thread: user commands arrive there, and the AnimationTimer heart beat
 * pulls finished frames out of the pipeline when they are due. The only state shared with
 * background threads is the pipeline's queues and the thread-safe pending audio queue.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    // Written by the decoder thread, read and cleared by the JavaFX thread.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    private final List<FrameProcessorOption> frameProcessorOptions;

    // JavaFX calls handle() on every pulse of its event loop; we use it as the display heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile;
    private FFmpegFrameGrabber grabber; // Opened and closed here, but only used by the decoder thread in between.
    private JavaFXFrameConverter converter; // Only used by the converter thread in between.
    private AudioPlayer audioPlayer;
    private FramePipeline pipeline;
    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean awaitingFirstFrame; // True from (re)start or seek until the first frame of the new position shows.
    private boolean audioOutputEnabled;
    private int playbackSession; // Lets us ignore error reports from a pipeline that has been replaced.
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessorOptions = List.copyOf(frameProcessorOptions);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    // Audio keeps being decoded and queued while muted (it is simply discarded when due), so
    // un-muting picks up in sync straight away with no gap.
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        // The seek itself happens on the decoder thread, so the GUI never waits for it.
        pipeline.seek(grabTimestampUs);
        resetPlaybackClock(logicalTimestampUs);
        flushAudioOutput();
        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();
        playbackSession++;

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            pipeline.start();
            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        JavaFXFrameConverter frameConverter = new JavaFXFrameConverter();
        converter = frameConverter;
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        boolean audioAvailable = grabber.hasAudio();
        AudioPlayer player = new AudioPlayer();
        audioPlayer = player;
        if (audioAvailable) {
            player.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }

        // Snapshot what the decoder thread needs, so it never reads model fields.
        String mediaName = mediaName(videoFile);
        int frames = totalVideoFrames;
        double rate = frameRate;
        int intRate = intFrameRate;
        int frameDurationUs = videoFrameDurationUs;

        DecoderTask.Callbacks callbacks = new DecoderTask.Callbacks() {
        	
            @Override
            public void firstImageFrame(Frame frame, int pixelFormat) throws Exception {
                pipeline.initialiseProcessors(new InfoVideo(
                        mediaName,
                        frames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        rate,
                        intRate,
                        frameDurationUs,
                        pixelFormat));
            }
            

            @Override
            public void audioDecoded(int generation, long timestampUs, Frame frame) {
                byte[] samples = player.copySamples(frame);
                if (samples.length > 0) {
                    pendingAudio.add(new PendingAudio(generation, timestampUs, samples));
                }
            }
        };

        int session = playbackSession;
        pipeline = new FramePipeline(
                grabber,
                audioAvailable,
                frameProcessorOptions,
                frameConverter::convert,
                callbacks,
                error -> Platform.runLater(() -> {
                    if (session == playbackSession && playbackOpen) {
                        handlePlaybackError(error);
                    }
                }));
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        awaitingFirstFrame = true;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // Called on every heart beat by the JavaFX thread. It never blocks and never does any decoding or
    // effect work: it only (1) keeps audio flowing and (2) shows the next finished frame when it is due.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);
        displayDueFrame(now);
    }

    private void displayDueFrame(long now) {
        // While paused we hold the picture, except for the single frame that shows a new position after a seek.
        if (pauseRequested && !awaitingFirstFrame) {
            return;
        }

        int generation = pipeline.currentGeneration();
        DisplayFrame frame = pipeline.peekDisplayFrame();
        while (frame != null && frame.generation() != generation) {
            pipeline.pollDisplayFrame(); // left over from before a seek
            frame = pipeline.peekDisplayFrame();
        }
        if (frame == null) {
            return;
        }

        if (frame.isEndOfStream()) {
            pipeline.pollDisplayFrame();
            finishPlayback();
            return;
        }

        long targetTimeNs;
        if (awaitingFirstFrame) {
            // The clock starts when the first frame is actually shown, not when it was decoded.
            firstTimestampUs = frame.timestampUs();
            playbackStartNs = now;
            if (pauseRequested) {
                pauseStartedNs = now;
            }
            awaitingFirstFrame = false;
            targetTimeNs = now;
        } else {
            long relativeTimestampUs = Math.max(0, frame.timestampUs() - firstTimestampUs);
            targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
            if (targetTimeNs > now) {
                return; // not due yet
            }
        }

        pipeline.pollDisplayFrame();
        showFrame(frame, targetTimeNs);
    }

    private void showFrame(DisplayFrame frame, long targetTimeNs) {
        long relativeTimestampUs = Math.max(0, frame.timestampUs() - firstTimestampUs);
        currentTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void writeDueAudio(long now) {
        if (audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        int generation = pipeline.currentGeneration();
        PendingAudio audio;
        while ((audio = pendingAudio.peek()) != null) {
            if (audio.generation() != generation) {
                pendingAudio.remove(); // from before a seek
                continue;
            }
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }
            if (!audioOutputEnabled) {
                pendingAudio.remove(); // muted: let it go by
                continue;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    // Order matters: all pipeline threads must be stopped and joined before the grabber and converter
    // they were using are closed.
    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        awaitingFirstFrame = false;

        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }

        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    // Created by the decoder thread, then consumed (and the offset advanced) only by the JavaFX thread.
    private static class PendingAudio {
        private final int generation;
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(int generation, long timestampUs, byte[] samples) {
            this.generation = generation;
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        int generation() {
            return generation;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}