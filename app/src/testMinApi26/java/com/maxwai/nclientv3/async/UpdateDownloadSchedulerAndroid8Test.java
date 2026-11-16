// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;

import android.os.Build;

import org.junit.Before;
import org.robolectric.annotation.Config;

@Config(sdk = 26)
public class UpdateDownloadSchedulerAndroid8Test extends UpdateDownloadSchedulerTest {
    @Before
    public void verifyAndroid8Runtime() {
        assertEquals(26, Build.VERSION.SDK_INT);
    }
}
