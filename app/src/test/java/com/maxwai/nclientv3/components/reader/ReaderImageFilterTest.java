// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReaderImageFilterTest {
    @Test
    public void invalidFilterValuesFallBackToOriginal() {
        assertEquals(ReaderImageFilter.NONE, ReaderImageFilter.sanitize(-1));
        assertEquals(ReaderImageFilter.NONE, ReaderImageFilter.sanitize(99));
        assertEquals(ReaderImageFilter.GRAYSCALE, ReaderImageFilter.sanitize(ReaderImageFilter.GRAYSCALE));
    }

    @Test
    public void imageAdjustmentsAreClampedToTheReaderRange() {
        assertEquals(-100, ReaderImageFilter.sanitizeAdjustment(-500));
        assertEquals(100, ReaderImageFilter.sanitizeAdjustment(500));
        assertEquals(0, ReaderImageFilter.sanitizeAdjustment(0));
    }
}
