/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessorOption> frameProcessorOptions = new ArrayList<>();
    
    private static final Map<String, Character> OPTION_LONG_TO_SHORT = Map.of(
            "--help", 'h',
            "--audio", 'a',
            "--maximise", 'x',
            "--monitor-1", '1',
            "--monitor-2", '2');

    
    public List<FrameProcessorOption> getFrameProcessorOptions() {
        return Collections.unmodifiableList(frameProcessorOptions);
    }

    public List<FrameProcessor> getFrameProcessors() {
        return FrameProcessorOption.createChain(frameProcessorOptions);
    }

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-")) {
                parseShortOptions(arg);
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
        	 boolean otherOptionsGiven = audioRequested || maximiseRequested
        	            || displayId != null || !frameProcessorOptions.isEmpty();
        	    if (!helpRequested || otherOptionsGiven) {
        	        errorMessage = "No video file specified.";
        	    }
        }
    }
    private void parseLongOption(String arg) {
        Character baseFlag = OPTION_LONG_TO_SHORT.get(arg);
        if (baseFlag != null) {
            applyShortOption(baseFlag);
            return;
        }
        FrameProcessorOption.fromLongFlag(arg).ifPresentOrElse(
                option -> frameProcessorOptions.add(option),
                () -> errorMessage = "Unknown option: " + arg);
    }

    // Handles single options ("-a") and stacked ones ("-jfddn"), left to right.
    private void parseShortOptions(String arg) {
        if (arg.length() == 1) {
            errorMessage = "Unknown option: " + arg;
            return;
        }
        for (char flag : arg.substring(1).toCharArray()) {
            applyShortOption(flag);
        }
    }

    private void applyShortOption(char flag) {
        switch (flag) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> FrameProcessorOption.fromShortFlag(flag).ifPresentOrElse(
                    option -> frameProcessorOptions.add(option),
                    () -> errorMessage = "Unknown option: -" + flag);
        }
    }
    
    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        FrameProcessorOption.helpLines().forEach(System.out::println);
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
