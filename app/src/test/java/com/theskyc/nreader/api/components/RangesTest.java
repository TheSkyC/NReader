package com.theskyc.nreader.api.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RangesTest {
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
