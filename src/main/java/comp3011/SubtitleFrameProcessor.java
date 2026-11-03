/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Renders speech-to-text subtitles onto frames. For now it burns a fixed block of pixels onto the
 * frame when subtitles are enabled, so the flag, button and hotkey can be verified before any real
 * text rendering or cloud transcription exists.
 */
public class SubtitleFrameProcessor extends FrameProcessor {
    private final SubtitleState state;

    public SubtitleFrameProcessor(SubtitleState state) {
        super();
        this.state = state;
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (!state.isEnabled()) {
            return;
        }
        if (frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }

        // Placeholder: a white bar near the bottom of the frame, where subtitles will later be drawn.
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int barTop = Math.max(0, height - height / 6);
        int barBottom = Math.min(height, barTop + 40);

        for (int y = barTop; y < barBottom; y++) {
            int rowStart = y * stride;
            for (int x = width / 4; x < width * 3 / 4; x++) {
                int base = rowStart + x * channels;
                for (int channel = 0; channel < channels; channel++) {
                    pixels.put(base + channel, (byte) 255);
                }
            }
        }
    }
}