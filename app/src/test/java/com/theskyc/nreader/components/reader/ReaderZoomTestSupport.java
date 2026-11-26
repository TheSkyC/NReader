// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.animation.ValueAnimator;

import java.lang.reflect.Field;

final class ReaderZoomTestSupport {
    private ReaderZoomTestSupport() {}

    static void advance(ContinuousZoomViewport viewport, long milliseconds) {
        animator(viewport).setCurrentPlayTime(milliseconds);
    }

    static void finish(ContinuousZoomViewport viewport) {
        animator(viewport).end();
    }

    static boolean isRunning(ContinuousZoomViewport viewport) {
        return findAnimator(viewport) != null;
    }

    private static ValueAnimator animator(ContinuousZoomViewport viewport) {
        ValueAnimator animator = findAnimator(viewport);
        if (animator == null) throw new AssertionError("Zoom animation did not start");
        return animator;
    }

    private static ValueAnimator findAnimator(ContinuousZoomViewport viewport) {
        try {
            Field field = ContinuousZoomViewport.class.getDeclaredField("zoomAnimator");
            field.setAccessible(true);
            return (ValueAnimator) field.get(viewport);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
