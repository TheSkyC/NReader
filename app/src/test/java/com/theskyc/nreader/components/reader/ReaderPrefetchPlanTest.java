package com.theskyc.nreader.components.reader;

import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.network.NetworkUtil;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderPrefetchPlanTest {
    @Test public void supportedSettingsHaveBoundedDefaults() {
        assertEquals(0, ReaderPrefetchPlan.pageCountForValue("0"));
        assertEquals(2, ReaderPrefetchPlan.pageCountForValue("2"));
        assertEquals(4, ReaderPrefetchPlan.pageCountForValue("4"));
        assertEquals(8, ReaderPrefetchPlan.pageCountForValue("8"));
        assertEquals(4, ReaderPrefetchPlan.pageCountForValue("invalid"));
    }

    @Test public void byteBudgetSettingsAcceptOnlySupportedLimits() {
        assertEquals(8L * 1024 * 1024, ReaderPrefetchPlan.byteBudgetForValue("8"));
        assertEquals(16L * 1024 * 1024, ReaderPrefetchPlan.byteBudgetForValue("16"));
        assertEquals(32L * 1024 * 1024, ReaderPrefetchPlan.byteBudgetForValue("32"));
        assertEquals(16L * 1024 * 1024, ReaderPrefetchPlan.byteBudgetForValue("invalid"));
        assertEquals(16L * 1024 * 1024, ReaderPrefetchPlan.byteBudgetForValue(null));
    }

    @Test public void meteredPrefetchRequiresExplicitOptIn() {
        assertTrue(ReaderPrefetchPlan.isAllowed(4, false, Global.DataUsageType.FULL,
            NetworkUtil.ConnectionType.WIFI));
        assertFalse(ReaderPrefetchPlan.isAllowed(4, false, Global.DataUsageType.FULL,
            NetworkUtil.ConnectionType.CELLULAR));
        assertTrue(ReaderPrefetchPlan.isAllowed(4, true, Global.DataUsageType.THUMBNAIL,
            NetworkUtil.ConnectionType.METERED));
        assertFalse(ReaderPrefetchPlan.isAllowed(4, true, Global.DataUsageType.NONE,
            NetworkUtil.ConnectionType.WIFI));
    }

    @Test public void windowFollowsReadingDirectionAndStopsAtBounds() {
        assertEquals(Arrays.asList(5, 6, 7), ReaderPrefetchPlan.pageWindow(8, 4, 1, 4, false));
        assertEquals(Arrays.asList(3, 2, 1, 0), ReaderPrefetchPlan.pageWindow(8, 4, -1, 4, false));
        assertEquals(Arrays.asList(), ReaderPrefetchPlan.pageWindow(8, 7, 1, 4, false));
        assertEquals(Arrays.asList(2, 1, 0), ReaderPrefetchPlan.pageWindow(8, 4, -1, 4, true));
    }
}
