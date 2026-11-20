// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

/** Maps the visible image point across a page-height change. */
final class ContinuousPageAnchor {
    private ContinuousPageAnchor() {}

    static int mappedTop(int imageTop, int viewportTop, int oldHeight, int newHeight) {
        if (oldHeight <= 0 || newHeight <= 0) throw new IllegalArgumentException("Invalid image height");
        if (imageTop >= viewportTop) return imageTop;
        double fraction = Math.min(1d, ((double) viewportTop - imageTop) / oldHeight);
        long top = Math.round(viewportTop - fraction * newHeight);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, top));
    }
}
