// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.widget.ListView;
import android.webkit.CookieManager;

import androidx.appcompat.app.AlertDialog;
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
import org.robolectric.shadows.ShadowDialog;
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

    @Test
    public void networkChoicesPreserveIntegerValuesAndAreIndependent() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        String wifiKey = application.getString(R.string.key_wifi_usage);
        String mobileKey = application.getString(R.string.key_mobile_usage);
        application.getSharedPreferences("Settings", 0).edit()
            .putInt(wifiKey, 1).putInt(mobileKey, 0).commit();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra(application.getPackageName() + ".TYPE", SettingsActivity.Type.DATA.ordinal());
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            Preference wifi = controller.get().fragment.findPreference(wifiKey);
            Preference mobile = controller.get().fragment.findPreference(mobileKey);
            assertEquals(application.getString(R.string.data_usage_thumb), wifi.getSummary());
            assertEquals(application.getString(R.string.data_usage_no), mobile.getSummary());
            wifi.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView list = dialog.getListView();
            assertEquals(1, list.getCheckedItemPosition());
            list.performItemClick(list.getChildAt(2), 2, list.getItemIdAtPosition(2));
            assertEquals(2, application.getSharedPreferences("Settings", 0).getInt(wifiKey, -1));
            assertEquals(0, application.getSharedPreferences("Settings", 0).getInt(mobileKey, -1));
            assertEquals(application.getString(R.string.data_usage_full), wifi.getSummary());
            controller.recreate();
            assertEquals(application.getString(R.string.data_usage_full),
                controller.get().fragment.findPreference(wifiKey).getSummary());
        }
    }

    @Test
    public void storageSectionTracksWhetherItsSettingIsAvailable() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_downloads");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            Preference category = controller.get().fragment.findPreference("settings_storage_category");
            Preference path = controller.get().fragment.findPreference(
                application.getString(R.string.preference_key_save_path));
            assertEquals(path.isVisible(), category.isVisible());
        }
    }

    @Test
    public void webCookieRemovalRequiresConfirmation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_data");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            CookieManager cookies = CookieManager.getInstance();
            cookies.setCookie("https://example.test", "test=value");
            Preference clear = controller.get().fragment.findPreference(
                application.getString(R.string.preference_key_cookie));
            clear.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertNotNull(cookies.getCookie("https://example.test"));
            clear.performClick();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(cookies.getCookie("https://example.test"));
        }
    }
}
