// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.Preference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.settings.Global;
import com.google.android.material.textfield.TextInputLayout;

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
    @Config(qualifiers = "w320dp-h640dp")
    public void settingsSectionRowsWrapLongTitlesAtLargeTextSize() {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            RecyclerView list = activity.fragment.getListView();
            int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
            int height = Math.round(640 * activity.getResources().getDisplayMetrics().density);
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                list.setLayoutDirection(direction);
                list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, width, height);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                int position = ((PreferenceGroupAdapter) list.getAdapter())
                    .getPreferenceAdapterPosition("settings_screen_reader");
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
                assertNotNull(holder);
                TextView title = holder.itemView.findViewById(android.R.id.title);
                TextView summary = holder.itemView.findViewById(android.R.id.summary);
                assertTrue("Section titles must allow wrapping", title.getMaxLines() > 1);
                title.setTextSize(24);
                title.setText("Search, tags and\ncontent filter settings");
                holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                holder.itemView.layout(0, 0, width, holder.itemView.getMeasuredHeight());
                assertTrue(title.getLayout().getLineCount() > 1);
                assertEquals(0, title.getLayout().getEllipsisCount(title.getLayout().getLineCount() - 1));
                assertTrue(title.getBottom() <= summary.getTop());
                assertTrue(summary.getBottom() <= holder.itemView.getHeight());
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    public void sectionTitleAllowsMultipleLinesAtLargeTextSize() {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_appearance");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            SettingsActivity activity = controller.get();
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.fontScale = 1.5f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            Toolbar toolbar = activity.findViewById(R.id.toolbar);
            TextView title = null;
            for (int index = 0; index < toolbar.getChildCount(); index++) {
                View child = toolbar.getChildAt(index);
                if (child instanceof TextView && activity.getSupportActionBar().getTitle().equals(((TextView) child).getText())) {
                    title = (TextView) child;
                }
            }
            assertNotNull(title);
            assertNull(title.getEllipsize());
            assertTrue(title.getMaxLines() > 1);
            title.setTextSize(22);
            title.setText("Appearance and language\nsettings");
            int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                toolbar.setLayoutDirection(direction);
                toolbar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                toolbar.layout(0, 0, width, toolbar.getMeasuredHeight());
                assertTrue(title.getLayout().getLineCount() > 1);
                assertEquals(0, title.getLayout().getEllipsisCount(title.getLayout().getLineCount() - 1));
                assertTrue(title.getLeft() >= 0 && title.getRight() <= toolbar.getWidth());
            }
        }
    }

    @Test
    public void listPreferenceDialogCancelsWhenTouchingInset() {
        Application application = RuntimeEnvironment.getApplication();
        String key = application.getString(R.string.preference_key_title_type);
        application.getSharedPreferences("Settings", 0).edit().putString(key, "pretty").commit();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_galleries");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            ListPreference preference = controller.get().fragment.findPreference(key);
            preference.performClick();
            controller.get().getSupportFragmentManager().executePendingTransactions();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            View decor = dialog.getWindow().getDecorView();
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
            MotionEvent up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 0, 0, 0);
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals("pretty", preference.getValue());
        }
    }

    @Test
    public void listPreferenceDialogPreservesSelectionAndCancellation() {
        Application application = RuntimeEnvironment.getApplication();
        String key = application.getString(R.string.preference_key_title_type);
        application.getSharedPreferences("Settings", 0).edit().putString(key, "pretty").commit();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_galleries");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            ListPreference preference = controller.get().fragment.findPreference(key);
            preference.performClick();
            controller.get().getSupportFragmentManager().executePendingTransactions();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, dialog.getListView().getCheckedItemPosition());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("pretty", preference.getValue());

            preference.performClick();
            controller.get().getSupportFragmentManager().executePendingTransactions();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView choices = dialog.getListView();
            choices.performItemClick(choices.getChildAt(0), 0, choices.getItemIdAtPosition(0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(preference.getEntryValues()[0].toString(), preference.getValue());
            controller.recreate();
            preference = controller.get().fragment.findPreference(key);
            assertEquals(preference.getEntryValues()[0].toString(), preference.getValue());
        }
    }

    @Test
    @Config(sdk = 31, qualifiers = "night")
    public void switchingDarkAndBlackRecreatesTheSettingsScreen() {
        Application application = RuntimeEnvironment.getApplication();
        String themeKey = application.getString(R.string.preference_key_theme_select);
        String blackKey = application.getString(R.string.preference_key_black_theme);
        application.getSharedPreferences("Settings", 0).edit()
            .putString(themeKey, "dark").putBoolean(blackKey, false).commit();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_appearance");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            SettingsActivity previous = controller.get();
            ListPreference preference = previous.fragment.findPreference(themeKey);
            assertTrue(preference.callChangeListener("black"));
            preference.setValue("black");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(previous, controller.get());
            assertEquals("black", ((ListPreference) controller.get().fragment.findPreference(themeKey)).getValue());
            assertEquals(android.graphics.Color.BLACK,
                com.google.android.material.color.MaterialColors.getColor(controller.get(), R.attr.colorSurface, 0));

            previous = controller.get();
            preference = previous.fragment.findPreference(themeKey);
            assertTrue(preference.callChangeListener("dark"));
            preference.setValue("dark");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(previous, controller.get());
            assertEquals("dark", ((ListPreference) controller.get().fragment.findPreference(themeKey)).getValue());
            assertNotEquals(android.graphics.Color.BLACK,
                com.google.android.material.color.MaterialColors.getColor(controller.get(), R.attr.colorSurface, 0));
        } finally {
            application.getSharedPreferences("Settings", 0).edit()
                .remove(themeKey).remove(blackKey).commit();
        }
    }

    @Test
    @Config(sdk = 31, qualifiers = "night")
    public void pausedScreenRefreshesAfterBlackThemeChanges() {
        Application application = RuntimeEnvironment.getApplication();
        String blackKey = application.getString(R.string.preference_key_black_theme);
        application.getSharedPreferences("Settings", 0).edit().putBoolean(blackKey, false).commit();
        try (ActivityController<AccountsActivity> controller =
                 Robolectric.buildActivity(AccountsActivity.class).setup()) {
            AccountsActivity previous = controller.get();
            controller.pause().stop();
            application.getSharedPreferences("Settings", 0).edit().putBoolean(blackKey, true).commit();
            controller.restart().start().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(previous, controller.get());
            assertEquals(android.graphics.Color.BLACK,
                com.google.android.material.color.MaterialColors.getColor(controller.get(), R.attr.colorSurface, 0));
        } finally {
            application.getSharedPreferences("Settings", 0).edit().remove(blackKey).commit();
        }
    }

    @Test
    public void successfulRestoreClosesSettingsStackButCancellationDoesNot() {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        try (ActivityController<SettingsActivity> root = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            root.get().fragment.findPreference("settings_screen_data").performClick();
            Intent childIntent = Shadows.shadowOf(root.get()).getNextStartedActivity();
            try (ActivityController<SettingsActivity> child =
                     Robolectric.buildActivity(SettingsActivity.class, childIntent).setup()) {
                child.get().restoreLastSnapshot();
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse(child.get().isFinishing());
                child.get().finishAfterDataRestore();
                assertTrue(child.get().isFinishing());
                assertEquals(Activity.RESULT_OK, Shadows.shadowOf(child.get()).getResultCode());
            }
            root.recreate();
            Shadows.shadowOf(root.get()).receiveResult(childIntent, Activity.RESULT_OK, null);
            assertTrue(root.get().isFinishing());
        }
        try (ActivityController<SettingsActivity> root = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            root.get().fragment.findPreference("settings_screen_data").performClick();
            Intent childIntent = Shadows.shadowOf(root.get()).getNextStartedActivity();
            Shadows.shadowOf(root.get()).receiveResult(childIntent, Activity.RESULT_CANCELED, null);
            assertFalse(root.get().isFinishing());
        }
    }

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
                    if (section[0].equals("settings_screen_data")) {
                        Preference favoriteCacheUsage = child.get().fragment.findPreference(
                            application.getString(R.string.preference_key_online_favorite_state_usage));
                        assertNotNull(favoriteCacheUsage);
                        assertNotNull(favoriteCacheUsage.getSummary());
                        assertFalse(favoriteCacheUsage.getSummary().toString().contains("%1$d"));
                    }
                    if (section[0].equals("settings_screen_search")) {
                        ListPreference display = child.get().fragment.findPreference(
                            application.getString(R.string.preference_key_tag_display_format));
                        assertNotNull(display);
                        assertEquals("translated_only", display.getValue());
                        display.performClick();
                        child.get().getSupportFragmentManager().executePendingTransactions();
                        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                        ListView choices = dialog.getListView();
                        assertNotNull(choices);
                        assertEquals(2, choices.getCount());
                        dialog.dismiss();
                        display.setValue("translated_only");
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
    public void invalidTagDisplayPreferenceDoesNotBreakSettings() {
        Application application = RuntimeEnvironment.getApplication();
        String key = application.getString(R.string.preference_key_tag_display_format);
        application.getSharedPreferences("Settings", 0).edit().putInt(key, 1).commit();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_search");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            ListPreference display = controller.get().fragment.findPreference(key);
            assertEquals("translated_only", display.getValue());
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
    public void customStoragePathDialogSavesEnteredPath() {
        Application application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        Intent intent = new Intent(application, SettingsActivity.class)
            .putExtra("settings_screen", "settings_screen_downloads");
        try (ActivityController<SettingsActivity> controller =
                 Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
            controller.get().fragment.manageCustomPath();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            TextInputLayout input = dialog.findViewById(R.id.autocomplete_input);
            AppCompatAutoCompleteTextView edit = dialog.findViewById(R.id.edit);
            assertEquals(application.getString(R.string.insert_path), input.getHint().toString());
            edit.setText("/example/path", false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String key = application.getString(R.string.preference_key_save_path);
            assertEquals("/example/path", application.getSharedPreferences("Settings", 0).getString(key, null));
            assertEquals("/example/path", controller.get().fragment.findPreference(key).getSummary());
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
