// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ContinuousPageAnchorTest {
    @Test public void preservesVisibleFractionWhenImageHeightChanges() {
        assertEquals(-10010, ContinuousPageAnchor.mappedTop(-10000, 0, 21578, 21600));
        assertEquals(-9980, ContinuousPageAnchor.mappedTop(-9990, 20, 21600, 21578));
    }

    @Test public void preservesTopOfImageBelowViewportAndBoundsExtremeInputs() {
        assertEquals(150, ContinuousPageAnchor.mappedTop(150, 20, 1000, 2000));
        assertEquals(20, ContinuousPageAnchor.mappedTop(20, 20, 1000, 2000));
        assertEquals(-1980, ContinuousPageAnchor.mappedTop(-2000, 20, 1000, 2000));
        assertEquals(-Integer.MAX_VALUE, ContinuousPageAnchor.mappedTop(Integer.MIN_VALUE, 0, 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ContinuousPageAnchor.mappedTop(0, 0, 0, 1));
    }
}
