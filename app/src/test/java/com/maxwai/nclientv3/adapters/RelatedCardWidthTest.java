// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RelatedCardWidthTest {
    @Test
    public void fitsTwoCardsOnPhoneAndBoundsWidthOnLargerScreens() {
        assertEquals(168, ListAdapter.relatedCardWidth(360, 1));
        assertEquals(200, ListAdapter.relatedCardWidth(900, 1));
        assertEquals(232, ListAdapter.relatedCardWidth(200, 2));
    }
}
