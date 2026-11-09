// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.classes;

public final class ReadingPosition {
    public final int page;
    public final float offset;

    public ReadingPosition(int page, float offset) {
        this.page = Math.max(1, page);
        this.offset = Float.isFinite(offset) ? Math.max(0f, Math.min(0.99f, offset)) : 0f;
    }

    public ReadingPosition clamp(int pageCount) {
        int boundedPage = Math.min(page, Math.max(1, pageCount));
        return new ReadingPosition(boundedPage, boundedPage == page ? offset : 0f);
    }
}
