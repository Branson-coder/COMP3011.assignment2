/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <your name and student number>
 */
package comp3011;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registry of the command-line selectable frame processors. Each constant holds its
 * flags, help description and a factory, so parsing and help text come from one source.
 * The factory is only invoked when the flag is used, never for help generation, because
 * processors keep static instance counters.
 */
public enum FrameProcessorOption {
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortFlag;
    private final String longFlag;
    private final String description;
    private final Supplier<FrameProcessor> factory;

    FrameProcessorOption(char shortFlag, String longFlag, String description, Supplier<FrameProcessor> factory) {
        this.shortFlag = shortFlag;
        this.longFlag = longFlag;
        this.description = description;
        this.factory = factory;
    }

    public FrameProcessor create() {
        return factory.get();
    }

    public static Optional<FrameProcessorOption> fromShortFlag(char flag) {
        return Arrays.stream(values()).filter(option -> option.shortFlag == flag).findFirst();
    }

    /** @param arg the full argument including the leading "--" */
    public static Optional<FrameProcessorOption> fromLongFlag(String arg) {
        return Arrays.stream(values()).filter(option -> ("--" + option.longFlag).equals(arg)).findFirst();
    }

    /** Help lines in declaration order, with descriptions aligned in a column. */
    public static List<String> helpLines() {
        int width = Arrays.stream(values()).mapToInt(option -> option.flags().length()).max().orElse(0);
        return Arrays.stream(values())
                .map(option -> String.format("  %-" + width + "s  %s", option.flags(), option.description))
                .toList();
    }

    private String flags() {
        return "-" + shortFlag + ", --" + longFlag;
    }
}