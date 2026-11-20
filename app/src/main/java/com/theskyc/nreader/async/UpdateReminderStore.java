// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public final class UpdateReminderStore {
    private static final String PREFERENCES = "UpdateReminder";
    private static final String SKIPPED_VERSION = "skipped_version";
    private static final String REMIND_AFTER = "remind_after";
    private final SharedPreferences preferences;

    public UpdateReminderStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean suppresses(@NonNull String version, long now) {
        if (version.equals(preferences.getString(SKIPPED_VERSION, null))) return true;
        return now < getRemindAfter();
    }

    long getRemindAfter() {
        return preferences.getLong(REMIND_AFTER, 0L);
    }

    public void remindLater(long now) {
        preferences.edit().putLong(REMIND_AFTER, now + 24L * 60L * 60L * 1000L).apply();
    }

    public void skip(@NonNull String version) {
        preferences.edit().putString(SKIPPED_VERSION, version).remove(REMIND_AFTER).apply();
    }
}
