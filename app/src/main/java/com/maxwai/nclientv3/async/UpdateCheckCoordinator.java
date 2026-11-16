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
