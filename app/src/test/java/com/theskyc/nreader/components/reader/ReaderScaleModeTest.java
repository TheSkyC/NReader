// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReaderScaleModeTest {
    @Test
    public void fitModesUseTheExpectedViewportBasis() {
        assertEquals(1f, ReaderScaleMode.calculate(ReaderScaleMode.FIT_SCREEN,
            1200, 2400, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
        assertEquals(1.2f, ReaderScaleMode.calculate(ReaderScaleMode.FIT_WIDTH,
            1200, 2400, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
        assertEquals(1f, ReaderScaleMode.calculate(ReaderScaleMode.FIT_HEIGHT,
            1200, 2400, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
        assertEquals(1.2f, ReaderScaleMode.calculate(ReaderScaleMode.FILL,
            1200, 2400, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
        assertEquals(2.4f, ReaderScaleMode.calculate(ReaderScaleMode.ORIGINAL,
            1200, 2400, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
    }

    @Test
    public void smartModePreservesTallPageHeuristicAndBoundsZoom() {
        assertEquals(2f, ReaderScaleMode.calculate(ReaderScaleMode.SMART,
            600, 3000, 600, 1000, 1080, 2400, 1f, 8f), 0.001f);
        assertEquals(3f, ReaderScaleMode.calculate(ReaderScaleMode.SMART,
            1000, 1000, 600, 1000, 600, 1000, 3f, 8f), 0.001f);
        assertEquals(8f, ReaderScaleMode.calculate(ReaderScaleMode.ORIGINAL,
            10000, 10000, 600, 1000, 600, 1000, 1f, 8f), 0.001f);
    }
}
