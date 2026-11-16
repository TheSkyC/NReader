// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import androidx.annotation.NonNull;

final class UpdateCheckCoordinator {
    private static final Object LOCK = new Object();
    private static long generation;

    private UpdateCheckCoordinator() { }

    static long begin() {
        synchronized (LOCK) {
            return ++generation;
        }
    }

    interface StartGate {
        boolean run(@NonNull Runnable action);
    }

    static long beginIfActive(@NonNull StartGate gate, @NonNull Runnable onStart) {
        synchronized (LOCK) {
            long[] started = {-1L};
            gate.run(() -> {
                onStart.run();
                started[0] = ++generation;
            });
            return started[0];
        }
    }

    static boolean runIfCurrent(long expected, @NonNull Runnable action) {
        synchronized (LOCK) {
            if (expected != generation) return false;
            action.run();
            return true;
        }
    }

    static boolean isCurrent(long expected) {
        synchronized (LOCK) {
            return expected == generation;
        }
    }
}
