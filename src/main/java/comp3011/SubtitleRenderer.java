/*
 * Authors:
 *   1. <Zixi Khoo a1925025 >
 */
package comp3011;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

/**
 * Rasterises subtitle text once into an ARGB bitmap, which frames then blend in. Drawing glyphs on
 * every frame would mean font rendering on every worker thread at the frame rate; rasterising only
 * when the text changes makes the per-frame cost a blend of a small cached image.
 *
 * <p>Not thread safe: each worker's processor holds its own renderer, so no locking is needed.</p>
 */
public class SubtitleRenderer {
    private static final double HEIGHT_FRACTION = 0.055; // font size relative to frame height
    private static final double OUTLINE_FRACTION = 0.14; // outline width relative to font size
    private static final double LINE_SPACING = 1.15;
    private static final int PADDING = 8;

    private String cachedText = null;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int[] pixels; // ARGB, row major
    private int imageWidth;
    private int imageHeight;

    /** Returns true when there is something to draw for this text at this frame size. */
    public boolean prepare(String text, int frameWidth, int frameHeight) {
        if (text.equals(cachedText) && frameWidth == cachedWidth && frameHeight == cachedHeight) {
            return imageWidth > 0;
        }

        cachedText = text;
        cachedWidth = frameWidth;
        cachedHeight = frameHeight;
        pixels = null;
        imageWidth = 0;
        imageHeight = 0;

        List<String> lines = SubtitleLayout.layout(text);
        if (lines.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }

        rasterise(lines, frameHeight);
        return imageWidth > 0;
    }

    public int width() {
        return imageWidth;
    }

    public int height() {
        return imageHeight;
    }

    /** ARGB pixel at (x, y) of the cached bitmap. */
    public int pixel(int x, int y) {
        return pixels[y * imageWidth + x];
    }

    private void rasterise(List<String> lines, int frameHeight) {
        float fontSize = (float) Math.max(12.0, frameHeight * HEIGHT_FRACTION);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontSize));
        float outlineWidth = (float) Math.max(1.5, fontSize * OUTLINE_FRACTION);

        // A throwaway context is needed to measure the text before the real image can be sized.
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(font);
        FontRenderContext context = probeGraphics.getFontRenderContext();

        TextLayout[] layouts = new TextLayout[lines.size()];
        double widest = 0;
        for (int index = 0; index < lines.size(); index++) {
            layouts[index] = new TextLayout(lines.get(index), font, context);
            widest = Math.max(widest, layouts[index].getBounds().getWidth());
        }
        double lineHeight = font.getSize() * LINE_SPACING;
        probeGraphics.dispose();

        imageWidth = (int) Math.ceil(widest + outlineWidth * 2 + PADDING * 2);
        imageHeight = (int) Math.ceil(lineHeight * lines.size() + outlineWidth * 2 + PADDING * 2);

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setStroke(new BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        double baseline = PADDING + outlineWidth + font.getSize() * 0.8;
        for (TextLayout layout : layouts) {
            // Centre each line, then stroke the outline in black before filling in white, so the text
            // stays legible whatever the frame behind it looks like.
            double lineWidth = layout.getBounds().getWidth();
            double x = (imageWidth - lineWidth) / 2.0 - layout.getBounds().getX();
            Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));

            graphics.setColor(Color.BLACK);
            graphics.draw(outline);
            graphics.setColor(Color.WHITE);
            graphics.fill(outline);

            baseline += lineHeight;
        }
        graphics.dispose();

        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }
}