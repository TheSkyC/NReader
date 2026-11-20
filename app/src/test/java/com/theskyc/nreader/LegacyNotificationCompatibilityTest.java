// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import com.theskyc.nreader.components.activities.LegacyDownloadNotificationActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class LegacyNotificationCompatibilityTest {
    @Test
    public void oldNotificationComponentsRemainPrivateAndResolveToCurrentActivities() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageManager manager = context.getPackageManager();
        ActivityInfo update = manager.getActivityInfo(new ComponentName(context,
            "com.maxwai.nclientv3.UpdateCenterActivity"), 0);
        ActivityInfo gallery = manager.getActivityInfo(new ComponentName(context,
            "com.maxwai.nclientv3.GalleryActivity"), 0);

        assertEquals(UpdateCenterActivity.class.getName(), update.targetActivity);
        assertEquals(LegacyDownloadNotificationActivity.class.getName(), gallery.targetActivity);
        assertFalse(update.exported);
        assertFalse(gallery.exported);
    }

    @Test
    public void oldDownloadNotificationOpensDownloadsWithoutReadingItsExtras() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, LegacyDownloadNotificationActivity.class);
        intent.putExtra(context.getPackageName() + ".GALLERY", "legacy payload");
        LegacyDownloadNotificationActivity activity = Robolectric.buildActivity(
            LegacyDownloadNotificationActivity.class, intent).setup().get();

        assertEquals(LocalActivity.class.getName(), Shadows.shadowOf(activity)
            .getNextStartedActivity().getComponent().getClassName());
        assertTrue(activity.isFinishing());
    }
}
