package com.theskyc.nreader.api.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RangesTest {
    @Test public void pageBoundsConflictOnlyWhenMinimumExceedsMaximum() {
        Ranges ranges = new Ranges();
        ranges.setFromPage(20);
        assertFalse(ranges.hasConflictingPageBounds());
        ranges.setToPage(10);
        assertTrue(ranges.hasConflictingPageBounds());
        ranges.setToPage(20);
        assertFalse(ranges.hasConflictingPageBounds());
        ranges.setToPage(30);
        assertFalse(ranges.hasConflictingPageBounds());
    }

    @Test public void conflictingUploadBoundsAreDetectedOnlyWhenComparable() {
        Ranges ranges = new Ranges();
        ranges.setFromDate(10);
        ranges.setFromDateUnit(Ranges.TimeUnit.DAY);
        ranges.setToDate(2);
        ranges.setToDateUnit(Ranges.TimeUnit.DAY);
        assertTrue(ranges.hasConflictingUploadBounds());
        ranges.setToDate(10);
        assertFalse(ranges.hasConflictingUploadBounds());
        ranges.setToDate(2);
        ranges.setToDateUnit(Ranges.TimeUnit.MONTH);
        assertFalse(ranges.hasConflictingUploadBounds());
        ranges.setToDate(Ranges.UNDEFINED);
        assertFalse(ranges.hasConflictingUploadBounds());
    }

    @Test public void equalNumbersWithDifferentUnitsRemainSeparateBounds() {
        Ranges ranges = new Ranges();
        ranges.setFromDate(1);
        ranges.setFromDateUnit(Ranges.TimeUnit.DAY);
        ranges.setToDate(1);
        ranges.setToDateUnit(Ranges.TimeUnit.MONTH);

        assertEquals("uploaded:>=1d uploaded:<=1m", ranges.toQuery());
    }

    @Test public void equalValuesWithTheSameUnitUseAnExactQuery() {
        Ranges ranges = new Ranges();
        ranges.setFromDate(1);
        ranges.setFromDateUnit(Ranges.TimeUnit.DAY);
        ranges.setToDate(1);
        ranges.setToDateUnit(Ranges.TimeUnit.DAY);

        assertEquals("uploaded:1d", ranges.toQuery());
    }
}
