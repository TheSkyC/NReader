// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class LauncherCompatibilityTest {
    @Test
    public void previousLauncherAliasesStillTargetTheCurrentPinActivity() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageManager manager = context.getPackageManager();
        ActivityInfo real = manager.getActivityInfo(new ComponentName(context,
            "com.maxwai.nclientv3.components.launcher.LauncherReal"),
            PackageManager.MATCH_DISABLED_COMPONENTS);
        ActivityInfo calculator = manager.getActivityInfo(new ComponentName(context,
            "com.maxwai.nclientv3.components.launcher.LauncherCalculator"),
            PackageManager.MATCH_DISABLED_COMPONENTS);

        assertEquals(PINActivity.class.getName(), real.targetActivity);
        assertEquals(PINActivity.class.getName(), calculator.targetActivity);
        assertTrue(real.enabled);
        assertFalse(calculator.enabled);
    }
}
