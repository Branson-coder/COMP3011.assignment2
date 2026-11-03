/*
 * Authors:
 *   1. a1925025 Zixi Khoo
 */
package comp3011;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Subtitle state shared by the GUI and every worker's copy of {@link SubtitleFrameProcessor}.
 *
 * <p>Each worker builds its own chain of processors, so the enabled flag and the current text
 * cannot live inside a processor: all copies must see the same state. Atomics give the JavaFX
 * thread a safe way to change them while the workers read them, with no locking on the hot path.</p>
 */
public final class SubtitleState {
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicReference<String> currentText = new AtomicReference<>("");

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

    /** The text to show right now, or "" for none. Replaced wholesale, never edited in place. */
    public void setCurrentText(String text) {
        currentText.set(text == null ? "" : text);
    }

    public String getCurrentText() {
        return currentText.get();
    }
}