// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.Nullable;

/** Identifies the image point held under a continuous-reader pinch gesture. */
final class ContinuousZoomAnchor {
    final int page;
    private final float imageX;
    private final float imageY;

    private ContinuousZoomAnchor(int page, float imageX, float imageY) {
        this.page = page;
        this.imageX = imageX;
        this.imageY = imageY;
    }

    @Nullable
    static ContinuousZoomAnchor capture(int page, float focusX, float focusY, float imageLeft,
                                        float imageTop, int imageWidth, int imageHeight) {
        if (page < 0 || imageWidth <= 0 || imageHeight <= 0 || !Float.isFinite(focusX)
            || !Float.isFinite(focusY) || !Float.isFinite(imageLeft) || !Float.isFinite(imageTop)) return null;
        float x = (focusX - imageLeft) / imageWidth;
        float y = (focusY - imageTop) / imageHeight;
        if (x < 0 || x > 1 || y < 0 || y > 1) return null;
        return new ContinuousZoomAnchor(page, x, y);
    }

    float horizontalOffset(float focusX, int contentWidth, int viewportWidth) {
        if (!Float.isFinite(focusX) || contentWidth <= 0 || viewportWidth <= 0) return 0;
        float minimum = Math.min(0, viewportWidth - contentWidth);
        return Math.max(minimum, Math.min(0, focusX - imageX * contentWidth));
    }

    int pageTop(float focusY, int imageTopInPage, int imageHeight, int viewportTop) {
        if (!Float.isFinite(focusY) || imageHeight <= 0) return viewportTop;
        return Math.round(focusY - imageTopInPage - imageY * imageHeight - viewportTop);
    }
}
