// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.app.Application;
import android.content.SharedPreferences;

import com.maxwai.nclientv3.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class MainGridSettingsTest {
    private Application application;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        preferences = application.getSharedPreferences("Settings", 0);
        preferences.edit().clear().commit();
    }

    @Test
    public void compactCyclesBetweenTwoAndThreeAndWideAddsFour() {
        MainGridSettings settings = new MainGridSettings(application);
        assertEquals(2, settings.columns(400, 1));
        settings.cycle(400, 1);
        assertEquals(3, settings.columns(400, 1));
        settings.cycle(400, 1);
        assertEquals(2, settings.columns(400, 1));
        assertEquals(2, settings.columns(720, 1));
        settings.cycle(720, 1);
        assertEquals(3, settings.columns(720, 1));
        settings.cycle(720, 1);
        assertEquals(4, settings.columns(720, 1));
        settings.cycle(720, 1);
        assertEquals(2, settings.columns(720, 1));
    }

    @Test
    public void boundariesAndLargeTextKeepActionTargetsInsideColumns() {
        assertEquals(2, MainGridSettings.maximumColumns(347, 1));
        assertEquals(3, MainGridSettings.maximumColumns(348, 1));
        assertEquals(3, MainGridSettings.maximumColumns(599, 1));
        assertEquals(4, MainGridSettings.maximumColumns(600, 1));
        assertEquals(2, MainGridSettings.maximumColumns(400, 1.5f));
        assertEquals(3, MainGridSettings.maximumColumns(600, 1.5f));
        assertEquals(1, MainGridSettings.maximumColumns(220, 1));
        assertEquals(3, MainGridSettings.maximumColumns(400, .85f));
    }

    @Test
    public void resizingAndReopeningRestoreSeparateChoicesWithoutOverwritingThem() {
        MainGridSettings settings = new MainGridSettings(application);
        settings.cycle(400, 1);
        settings.cycle(720, 1);
        settings.cycle(720, 1);
        assertEquals(3, settings.columns(400, 1));
        assertEquals(4, settings.columns(720, 1));
        assertEquals(2, settings.columns(320, 1));
        settings.cycle(320, 1);
        assertEquals(3, settings.columns(400, 1));
        assertEquals(2, settings.columns(400, 1.5f));
        MainGridSettings reopened = new MainGridSettings(application);
        assertEquals(3, reopened.columns(400, 1));
        assertEquals(4, reopened.columns(720, 1));
    }

    @Test
    public void legacyChoicesAreSeededOnceAndTheirKeysArePreserved() {
        String portrait = application.getString(R.string.key_column_port_main);
        String landscape = application.getString(R.string.key_column_land_main);
        preferences.edit().putInt(portrait, 1).putInt(landscape, 4).commit();
        MainGridSettings settings = new MainGridSettings(application);
        assertEquals(2, settings.columns(400, 1));
        assertEquals(4, settings.columns(720, 1));
        assertEquals(1, preferences.getInt(portrait, 0));
        assertEquals(4, preferences.getInt(landscape, 0));
        settings.cycle(400, 1);
        settings.cycle(720, 1);
        MainGridSettings reopened = new MainGridSettings(application);
        assertEquals(3, reopened.columns(400, 1));
        assertEquals(2, reopened.columns(720, 1));
    }

    @Test
    public void downloadChoicesMigrateOnceAndRemainIndependentFromOnlineChoices() {
        String portrait = application.getString(R.string.key_column_port_down);
        String landscape = application.getString(R.string.key_column_land_down);
        preferences.edit().putInt(portrait, 3).putInt(landscape, 4).commit();
        MainGridSettings downloads = MainGridSettings.forDownloads(application);
        MainGridSettings online = new MainGridSettings(application);
        assertEquals(3, downloads.columns(400, 1));
        assertEquals(4, downloads.columns(720, 1));
        assertEquals(2, online.columns(400, 1));
        assertEquals(2, online.columns(720, 1));
        downloads.cycle(400, 1);
        downloads.cycle(720, 1);
        online.cycle(400, 1);
        MainGridSettings reopened = MainGridSettings.forDownloads(application);
        assertEquals(2, reopened.columns(400, 1));
        assertEquals(2, reopened.columns(720, 1));
        assertEquals(3, online.columns(400, 1));
        assertEquals(3, preferences.getInt(portrait, 0));
        assertEquals(4, preferences.getInt(landscape, 0));
        reopened.cycle(400, 1);
        reopened.cycle(720, 1);
        reopened.cycle(720, 1);
        assertEquals(2, reopened.columns(320, 1));
        assertEquals(2, reopened.columns(400, 1.5f));
        assertEquals(3, reopened.columns(400, 1));
        assertEquals(4, reopened.columns(720, 1));
        assertEquals(2, online.columns(720, 1));
    }

    @Test
    public void invalidStoredCountsCannotCreateUnsupportedLayouts() {
        preferences.edit()
            .putInt(application.getString(R.string.key_main_grid_compact_columns), Integer.MAX_VALUE)
            .putInt(application.getString(R.string.key_main_grid_wide_columns), Integer.MIN_VALUE).commit();
        MainGridSettings settings = new MainGridSettings(application);
        assertEquals(3, settings.columns(400, 1));
        assertEquals(2, settings.columns(720, 1));
    }
}
