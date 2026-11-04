/*
 * Authors:
 *   1. <Zixi Khoo a1925025>
 */
package comp3011;

/**
 * One subtitle segment as returned by the subtitle service: the text and the window of playback
 * time, in microseconds, over which it should be displayed.
 */
public record SubtitleSegment(long startUs, long endUs, String text) {

    public boolean covers(long timestampUs) {
        return timestampUs >= startUs && timestampUs < endUs;
    }
}