// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.Manifest;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.NotificationSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 32)
public class UpdateNotificationTest {
    private Context context;
    private NotificationManager manager;

    @After
    public void tearDown() {
        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .remove(context.getString(R.string.preference_key_maximum_notification)).commit();
        NotificationSettings.initializeNotificationManager(context);
    }

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        context = application;
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("UpdateReminder", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).edit().clear().commit();
        NotificationSettings.initializeNotificationManager(context);
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(Global.CHANNEL_ID4, "Updates", NotificationManager.IMPORTANCE_NONE));
    }

    @Test
    public void blockedChannelDoesNotConsumeAvailableReminder() {
        UpdateState state = UpdateState.available("1.1.0", 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);

        enableChannel();
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
        UpdateNotification.clear(context);
        UpdateNotification.publish(context, state);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void blockedChannelDoesNotConsumeReadyReminder() {
        UpdateState state = UpdateState.readyToInstall("1.1.0", "/update.apk", 3L, 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_ready"));
        assertEquals(0, manager.getActiveNotifications().length);

        enableChannel();
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
        UpdateNotification.clear(context);
        UpdateNotification.publish(context, state);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void missingChannelDoesNotConsumeReminder() {
        manager.deleteNotificationChannel(Global.CHANNEL_ID4);
        UpdateNotification.publish(context, UpdateState.available("1.1.0", 1L));
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void zeroNotificationLimitDoesNotConsumeReminder() {
        enableChannel();
        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .putInt(context.getString(R.string.preference_key_maximum_notification), 0).commit();
        NotificationSettings.initializeNotificationManager(context);
        UpdateState state = UpdateState.available("1.1.0", 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);

        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .remove(context.getString(R.string.preference_key_maximum_notification)).commit();
        NotificationSettings.initializeNotificationManager(context);
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
    }

    private void enableChannel() {
        manager.getNotificationChannel(Global.CHANNEL_ID4).setImportance(NotificationManager.IMPORTANCE_DEFAULT);
    }
}
