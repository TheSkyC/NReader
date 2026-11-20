// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ContinuousZoomAnchorTest {
    @Test
    public void keepsTheSameImagePointUnderTheFingersAfterResize() {
        ContinuousZoomAnchor anchor = ContinuousZoomAnchor.capture(4, 250, 400, 0, -100, 500, 1000);
        assertNotNull(anchor);
        assertEquals(4, anchor.page);
        assertEquals(-250f, anchor.horizontalOffset(250, 1000, 500), 0.001f);
        assertEquals(-600, anchor.pageTop(400, 0, 2000, 0));
        assertEquals(-200f, anchor.horizontalOffset(300, 1000, 500), 0.001f);
        assertEquals(-550, anchor.pageTop(450, 0, 2000, 0));
    }

    @Test
    public void clampsHorizontalPanAtBothEdges() {
        ContinuousZoomAnchor anchor = ContinuousZoomAnchor.capture(0, 20, 50, 0, 0, 100, 100);
        assertNotNull(anchor);
        assertEquals(0f, anchor.horizontalOffset(200, 200, 100), 0.001f);
        assertEquals(-100f, anchor.horizontalOffset(-100, 200, 100), 0.001f);
        assertEquals(0f, anchor.horizontalOffset(50, 50, 100), 0.001f);
    }

    @Test
    public void accountsForPageInsetsAndViewportPadding() {
        ContinuousZoomAnchor anchor = ContinuousZoomAnchor.capture(1, 100, 250, -100, 50, 400, 400);
        assertNotNull(anchor);
        assertEquals(-300f, anchor.horizontalOffset(100, 800, 400), 0.001f);
        assertEquals(-190, anchor.pageTop(250, 20, 800, 20));
    }

    @Test
    public void refusesAnAnchorOutsideAValidLoadedImage() {
        assertNull(ContinuousZoomAnchor.capture(-1, 0, 0, 0, 0, 100, 100));
        assertNull(ContinuousZoomAnchor.capture(0, 101, 50, 0, 0, 100, 100));
        assertNull(ContinuousZoomAnchor.capture(0, 50, 50, 0, 0, 0, 100));
        assertNull(ContinuousZoomAnchor.capture(0, Float.NaN, 0, 0, 0, 100, 100));
        assertNull(ContinuousZoomAnchor.capture(0, 0, 0, 0, Float.POSITIVE_INFINITY, 100, 100));
    }
}
