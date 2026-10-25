/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    @TempDir
    Path tempDir;

    private String videoPath;

    @BeforeEach
    void createVideoFile() throws IOException {
        videoPath = Files.createFile(tempDir.resolve("movie.mp4")).toString();
    }

    private CommandLineController parseWithVideo(String... options) {
        List<String> args = new ArrayList<>(Arrays.asList(options));
        args.add(videoPath);
        return new CommandLineController(args.toArray(new String[0]));
    }

    private List<Class<?>> processorTypes(CommandLineController controller) {
        return controller.getFrameProcessors().stream()
                .<Class<?>>map(FrameProcessor::getClass)
                .toList();
    }

    @Test
    void noProcessorsByDefault() {
        CommandLineController controller = parseWithVideo();
        assertTrue(controller.getFrameProcessors().isEmpty());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void stackedShortFlagsKeepOrderAndRepeats() {
        CommandLineController controller = parseWithVideo("-jfddn");
        assertEquals(List.of(FrameJitterer.class, FrameFlickerer.class,
                FrameDuster.class, FrameDuster.class, FrameNumberer.class),
                processorTypes(controller));
    }

    @Test
    void longShortAndStackedOptionsMix() {
        CommandLineController controller = parseWithVideo("-jf", "--dust-frames", "-d", "--number-frames");
        assertEquals(List.of(FrameJitterer.class, FrameFlickerer.class,
                FrameDuster.class, FrameDuster.class, FrameNumberer.class),
                processorTypes(controller));
    }

    @Test
    void allElevenProcessorsInSpecOrder() {
        CommandLineController controller = parseWithVideo("-nssnfwyvdjmbp");
        assertEquals(13, controller.getFrameProcessors().size());
        assertEquals(FrameNumberer.class, controller.getFrameProcessors().get(0).getClass());
        assertEquals(FrameScratcher.class, controller.getFrameProcessors().get(1).getClass());
        assertEquals(FrameScratcher.class, controller.getFrameProcessors().get(2).getClass());
        assertEquals(FrameNumberer.class, controller.getFrameProcessors().get(3).getClass());
        assertEquals(FramePepperer.class, controller.getFrameProcessors().get(12).getClass());
    }

    @Test
    void repeatedFlagsCreateSeparateInstances() {
        CommandLineController controller = parseWithVideo("-dd");
        assertNotSame(controller.getFrameProcessors().get(0), controller.getFrameProcessors().get(1));
    }

    @Test
    void stackedBaseOptionsWorkWithProcessors() {
        CommandLineController controller = parseWithVideo("-nax");
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getFrameProcessors().size());
    }

    @Test
    void displayOptionIsParsed() {
        assertEquals(2, parseWithVideo("--monitor-2").getDisplayId());
        assertNull(parseWithVideo().getDisplayId());
    }

    @Test
    void unknownStackedFlagIsAnError() {
        CommandLineController controller = parseWithVideo("-nq");
        assertEquals("Unknown option: -q", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void unknownLongFlagIsAnError() {
        CommandLineController controller = parseWithVideo("--sparkle-frames");
        assertEquals("Unknown option: --sparkle-frames", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void missingVideoFileIsAnError() {
        CommandLineController controller = new CommandLineController(new String[] { "-n" });
        assertFalse(controller.shouldLaunchApplication());
        assertEquals(1, controller.getExitCode());
    }

    @Test
    void helpListsFrameProcessorsInOrder() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            new CommandLineController(new String[] { "--help" });
        } finally {
            System.setOut(original);
        }

        String help = captured.toString().replace("\r\n", "\n");
        assertTrue(help.contains("\n\nFrame processors:\n"));
        assertTrue(help.contains("-n, --number-frames"));
        assertTrue(help.indexOf("--number-frames") < help.indexOf("--scratch-frames"));
        assertTrue(help.indexOf("--bleed-frames") < help.indexOf("--pepper-frames"));
        assertTrue(help.contains("Frame processors are applied in command-line order and may be repeated."));
    }
    
    @Test
    void helpWithOtherOptionsButNoVideoIsAnError() {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertEquals(1, new CommandLineController(new String[] { "--number-frames", "-h" }).getExitCode());
            assertEquals(0, new CommandLineController(new String[] { "-h" }).getExitCode());
        } finally {
            System.setOut(original);
        }
    }
}