// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import com.maxwai.nclientv3.utility.network.NetworkUtil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GlobalNetworkPolicyTest {
    @Test
    public void unknownNetworkFailsClosedAndMeteredNetworksUseMobilePolicy() {
        assertEquals(Global.DataUsageType.NONE, Global.getDownloadPolicy(
            NetworkUtil.ConnectionType.UNKNOWN, Global.DataUsageType.FULL, Global.DataUsageType.NONE));
        assertEquals(Global.DataUsageType.NONE, Global.getDownloadPolicy(
            NetworkUtil.ConnectionType.METERED, Global.DataUsageType.FULL, Global.DataUsageType.NONE));
        assertEquals(Global.DataUsageType.FULL, Global.getDownloadPolicy(
            NetworkUtil.ConnectionType.WIFI, Global.DataUsageType.FULL, Global.DataUsageType.NONE));
        assertEquals(Global.DataUsageType.NONE, Global.getDownloadPolicy(
            NetworkUtil.ConnectionType.CELLULAR, Global.DataUsageType.FULL, Global.DataUsageType.NONE));
    }
}
