/*
 * Authors:
 *   1. <your name and student number>
 */
package comp3011;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subtitle state shared by the GUI and every worker's copy of {@link SubtitleFrameProcessor}.
 *
 * <p>Each worker builds its own chain of processors, so the enabled flag and (later) the subtitle
 * text cannot live inside a processor: all copies must see the same state. An AtomicBoolean gives
 * the JavaFX thread a safe way to toggle it while the workers read it.</p>
 */
public final class SubtitleState {
    private final AtomicBoolean enabled = new AtomicBoolean();

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public boolean toggle() {
        boolean updated;
        do {
            updated = !enabled.get();
        } while (!enabled.compareAndSet(!updated, updated));
        return updated;
    }
}