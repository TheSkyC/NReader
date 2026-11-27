// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OnlineFavoriteStateQuotaTest {
    @Test
    public void supportedValuesUseEntryCounts() {
        assertEquals(512, OnlineFavoriteStateQuota.entriesForValue("512"));
        assertEquals(2048, OnlineFavoriteStateQuota.entriesForValue("2048"));
        assertEquals(5120, OnlineFavoriteStateQuota.entriesForValue("5120"));
        assertEquals(10240, OnlineFavoriteStateQuota.entriesForValue("10240"));
    }

    @Test
    public void invalidValuesFallBackToExistingLimit() {
        assertEquals(512, OnlineFavoriteStateQuota.entriesForValue(null));
        assertEquals(512, OnlineFavoriteStateQuota.entriesForValue("0"));
        assertEquals(512, OnlineFavoriteStateQuota.entriesForValue("20480"));
    }
}
