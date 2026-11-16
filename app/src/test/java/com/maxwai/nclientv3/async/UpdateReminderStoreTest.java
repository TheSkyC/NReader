// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class UpdateReminderStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateReminder", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("UpdateReminder", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void skipSuppressesOnlyTheSelectedVersion() {
        UpdateReminderStore store = new UpdateReminderStore(context);
        store.skip("1.1.0");

        assertTrue(store.suppresses("1.1.0", 0L));
        assertFalse(store.suppresses("1.2.0", 0L));
    }

    @Test
    public void remindLaterSuppressesUntilTheNextDay() {
        UpdateReminderStore store = new UpdateReminderStore(context);
        store.remindLater(1000L);

        assertTrue(store.suppresses("1.1.0", 1000L + 60L * 60L * 1000L));
        assertFalse(store.suppresses("1.1.0", 1000L + 25L * 60L * 60L * 1000L));
    }

    @Test
    public void remindedVersionNotifiesAgainAfterDeadlineButOnlyOncePerReminder() {
        long day = 24L * 60L * 60L * 1000L;
        UpdateReminderStore store = new UpdateReminderStore(context);

        assertTrue(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 1000L));
        assertFalse(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 2000L));
        store.remindLater(2000L);
        assertFalse(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 2000L + day - 1L));
        assertTrue(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 2000L + day));
        assertFalse(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 2001L + day));

        store.remindLater(3000L + day);
        assertTrue(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 3000L + 2L * day));
        assertFalse(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 3001L + 2L * day));
    }

    @Test
    public void skippedVersionStaysSuppressedAndNewVersionCanNotify() {
        UpdateReminderStore store = new UpdateReminderStore(context);
        assertTrue(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", 1000L));
        store.skip("1.1.0");

        assertFalse(UpdateNotification.shouldNotifyAvailable(context, "1.1.0", Long.MAX_VALUE));
        assertTrue(UpdateNotification.shouldNotifyAvailable(context, "1.2.0", Long.MAX_VALUE));
    }
}
