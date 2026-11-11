// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility.network;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NetworkUtilTest {
    @Test
    public void classifiesOnlyValidatedNetworksAndSeparatesMeteredWifi() {
        assertEquals(NetworkUtil.ConnectionType.UNKNOWN, NetworkUtil.classifyCapabilities(null));
        assertEquals(NetworkUtil.ConnectionType.UNKNOWN, NetworkUtil.classifyCapabilities(
            true, false, false, true, false, true));
        assertEquals(NetworkUtil.ConnectionType.WIFI, NetworkUtil.classifyCapabilities(
            true, true, false, true, false, true));
        assertEquals(NetworkUtil.ConnectionType.METERED, NetworkUtil.classifyCapabilities(
            true, true, false, true, false, false));
        assertEquals(NetworkUtil.ConnectionType.CELLULAR, NetworkUtil.classifyCapabilities(
            true, true, true, false, false, false));
    }
}
