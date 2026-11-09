// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.classes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReadingPositionTest {
    @Test
    public void invalidValuesFallBackToFirstPage() {
        ReadingPosition position = new ReadingPosition(-8, Float.NaN).clamp(0);
        assertEquals(1, position.page);
        assertEquals(0f, position.offset, 0f);
    }

    @Test
    public void shortenedGalleryDiscardsOldOffset() {
        ReadingPosition position = new ReadingPosition(30, 0.5f).clamp(12);
        assertEquals(12, position.page);
        assertEquals(0f, position.offset, 0f);
    }

    @Test
    public void scrollPositionSurvivesClamping() {
        ReadingPosition position = new ReadingPosition(7, 0.63f).clamp(20);
        assertEquals(7, position.page);
        assertEquals(0.63f, position.offset, 0f);
        assertEquals(0.99f, new ReadingPosition(1, 2f).offset, 0f);
        assertEquals(0f, new ReadingPosition(1, -2f).offset, 0f);
        assertEquals(0f, new ReadingPosition(1, Float.POSITIVE_INFINITY).offset, 0f);
    }
}
