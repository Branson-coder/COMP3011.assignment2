/*
 * Authors:
 *   1. <Zixi Khoo a1925025>
 */
package comp3011;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Renders the current subtitle text onto frames, low and centred, with a white sans serif face and
 * a black outline so it stays legible over any background.
 *
 * <p>The text is rasterised by {@link SubtitleRenderer} only when it changes; each frame then alpha
 * blends the cached bitmap into the frame's pixels. The renderer belongs to this processor instance,
 * and each worker has its own instance, so nothing here needs locking.</p>
 */
public class SubtitleFrameProcessor extends FrameProcessor {
    private static final double BOTTOM_MARGIN_FRACTION = 0.06;

    private final SubtitleState state;
    private final SubtitleRenderer renderer = new SubtitleRenderer();

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
        if (videoInfo.imageChannels() < 3) {
            return;
        }

        String text = state.getCurrentText();
        if (text.isEmpty() || !renderer.prepare(text, videoInfo.imageWidth(), videoInfo.imageHeight())) {
            return;
        }

        blend(pixels);
    }

    private void blend(ByteBuffer pixels) {
        int frameWidth = videoInfo.imageWidth();
        int frameHeight = videoInfo.imageHeight();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        int left = Math.max(0, (frameWidth - renderer.width()) / 2);
        int top = Math.max(0, frameHeight - renderer.height()
                - (int) Math.round(frameHeight * BOTTOM_MARGIN_FRACTION));

        int drawWidth = Math.min(renderer.width(), frameWidth - left);
        int drawHeight = Math.min(renderer.height(), frameHeight - top);

        for (int y = 0; y < drawHeight; y++) {
            int rowStart = (top + y) * stride;
            for (int x = 0; x < drawWidth; x++) {
                int argb = renderer.pixel(x, y);
                int alpha = (argb >>> 24);
                if (alpha == 0) {
                    continue;
                }

                int base = rowStart + (left + x) * channels;
                // JavaCV supplies these decoded frames in BGR byte order.
                blendChannel(pixels, base, argb & 0xff, alpha);
                blendChannel(pixels, base + 1, (argb >>> 8) & 0xff, alpha);
                blendChannel(pixels, base + 2, (argb >>> 16) & 0xff, alpha);
            }
        }
    }

    private void blendChannel(ByteBuffer pixels, int index, int source, int alpha) {
        int destination = Byte.toUnsignedInt(pixels.get(index));
        int blended = (source * alpha + destination * (255 - alpha)) / 255;
        pixels.put(index, (byte) blended);
    }
}