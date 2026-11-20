// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

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

        assertTrue(shouldNotify("1.1.0", 1000L));
        markNotified("1.1.0");
        assertFalse(shouldNotify("1.1.0", 2000L));
        store.remindLater(2000L);
        assertFalse(shouldNotify("1.1.0", 2000L + day - 1L));
        assertTrue(shouldNotify("1.1.0", 2000L + day));
        markNotified("1.1.0");
        assertFalse(shouldNotify("1.1.0", 2001L + day));

        store.remindLater(3000L + day);
        assertTrue(shouldNotify("1.1.0", 3000L + 2L * day));
        markNotified("1.1.0");
        assertFalse(shouldNotify("1.1.0", 3001L + 2L * day));
    }

    @Test
    public void skippedVersionStaysSuppressedAndNewVersionCanNotify() {
        UpdateReminderStore store = new UpdateReminderStore(context);
        assertTrue(shouldNotify("1.1.0", 1000L));
        markNotified("1.1.0");
        store.skip("1.1.0");

        assertFalse(shouldNotify("1.1.0", Long.MAX_VALUE));
        assertTrue(shouldNotify("1.2.0", Long.MAX_VALUE));
    }

    private boolean shouldNotify(String version, long now) {
        return UpdateNotification.shouldNotifyAvailable(context, version, now, new UpdateReminderStore(context).getRemindAfter());
    }

    private void markNotified(String version) {
        UpdateNotification.markAvailable(context, version, new UpdateReminderStore(context).getRemindAfter());
    }
}
