// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SettingsNavigationTest {
    @Test
    public void sectionsOpenWithTheirPreferencesAndSurviveRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("Settings", 0).edit()
            .putBoolean("change_page_buttons", false).commit();
        Global.initFromShared(application);
        String[][] sections = {
            {"settings_screen_reader", "preference_key_change_page_buttons"},
            {"settings_screen_galleries", "preference_key_col_screen"},
            {"settings_screen_search", "preference_key_tag_display_language"},
            {"settings_screen_downloads", "preference_key_data_screen"},
            {"settings_screen_accounts", "preference_key_account_login"},
            {"settings_screen_appearance", "preference_key_language"},
            {"settings_screen_privacy", "preference_key_has_credentials"},
            {"settings_screen_data", "preference_key_export"},
            {"settings_screen_about", "preference_key_version"}
        };
        try (DatabaseHelper database = new DatabaseHelper(application);
             ActivityController<SettingsActivity> root = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            database.getWritableDatabase();
            assertEquals(sections.length, root.get().fragment.getPreferenceScreen().getPreferenceCount());
            for (String[] section : sections) {
                PreferenceScreen screen = root.get().fragment.findPreference(section[0]);
                assertNotNull(screen);
                screen.performClick();
                Intent intent = Shadows.shadowOf(root.get()).getNextStartedActivity();
                assertNotNull(intent);
                try (ActivityController<SettingsActivity> child =
                         Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
                    assertEquals(section[0], child.get().fragment.getPreferenceScreen().getKey());
                    int resourceId = application.getResources().getIdentifier(section[1], "string", application.getPackageName());
                    assertNotEquals(0, resourceId);
                    Preference preference = child.get().fragment.findPreference(application.getString(resourceId));
                    assertNotNull(section[1], preference);
                    assertEquals(screen.getTitle(), child.get().getSupportActionBar().getTitle());
                    if (section[0].equals("settings_screen_reader")) {
                        assertFalse(((SwitchPreferenceCompat) preference).isChecked());
                    }
                    child.recreate();
                    assertEquals(section[0], child.get().fragment.getPreferenceScreen().getKey());
                    assertNotNull(child.get().fragment.findPreference(application.getString(resourceId)));
                }
            }
            assertFalse(application.getSharedPreferences("Settings", 0).getBoolean("change_page_buttons", true));
        }
    }
}
