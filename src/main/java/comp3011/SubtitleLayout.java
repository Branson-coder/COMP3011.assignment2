/*
 * Authors:
 *   1. <Zixi Khoo a1925025>
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks subtitle text into display lines following the production guidelines in the specification:
 * at most 42 characters per line, at most two lines, one line preferred when the text fits, and
 * breaks placed after punctuation where possible.
 */
public final class SubtitleLayout {
    public static final int MAX_CHARACTERS_PER_LINE = 42;
    public static final int MAX_LINES = 2;

    private SubtitleLayout() {
    }

    public static List<String> layout(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }

        String trimmed = text.strip().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return lines;
        }
        if (trimmed.length() <= MAX_CHARACTERS_PER_LINE) {
            lines.add(trimmed); // one line is preferred whenever the text fits on one
            return lines;
        }

        int breakAt = chooseBreak(trimmed);
        lines.add(trimmed.substring(0, breakAt).strip());

        String remainder = trimmed.substring(breakAt).strip();
        if (remainder.length() > MAX_CHARACTERS_PER_LINE) {
            // Two lines is the maximum, so anything that still does not fit is truncated.
            remainder = remainder.substring(0, MAX_CHARACTERS_PER_LINE - 1).strip() + "\u2026";
        }
        lines.add(remainder);
        return lines;
    }

    // Prefer a break just after punctuation, otherwise the last word boundary that fits.
    private static int chooseBreak(String text) {
        int limit = Math.min(MAX_CHARACTERS_PER_LINE, text.length() - 1);
        int punctuationBreak = -1;
        int spaceBreak = -1;

        for (int index = 0; index <= limit; index++) {
            char character = text.charAt(index);
            if (character == ' ') {
                spaceBreak = index;
                char previous = text.charAt(index - 1);
                if (previous == ',' || previous == '.' || previous == '!'
                        || previous == '?' || previous == ';' || previous == ':') {
                    punctuationBreak = index;
                }
            }
        }

        if (punctuationBreak > 0) {
            return punctuationBreak;
        }
        if (spaceBreak > 0) {
            return spaceBreak;
        }
        return limit; // a single word longer than the line: break it mid-word
    }
}