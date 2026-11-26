// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class ReaderStatusSettingsTest {
    @Test
    public void defaultsMatchTheRequestedReaderLayout() {
        SharedPreferences preferences = Robolectric.buildActivity(android.app.Activity.class)
            .setup().get().getSharedPreferences("Settings", 0);

        ReaderStatusSettings settings = ReaderStatusSettings.load(preferences);

        assertTrue(settings.enabled);
        assertTrue(settings.timeEnabled);
        assertTrue(settings.batteryEnabled);
        assertTrue(settings.progressEnabled);
        assertEquals(ReaderStatusSettings.VISIBILITY_ALWAYS, settings.timeVisibility);
        assertEquals(ReaderStatusSettings.VISIBILITY_ALWAYS, settings.batteryVisibility);
        assertEquals(ReaderStatusSettings.VISIBILITY_IDLE, settings.progressVisibility);
        assertEquals(ReaderStatusSettings.DEFAULT_IDLE_DELAY_SECONDS, settings.idleDelaySeconds);
        assertEquals(ReaderStatusSettings.BATTERY_ICON_AND_PERCENT, settings.batteryFormat);
        assertEquals(ReaderStatusSettings.PROGRESS_COUNT, settings.progressFormat);
        assertEquals(ReaderStatusSettings.STYLE_BACKED, settings.style);
        assertFalse(settings.timePosition.custom);
        assertFalse(settings.batteryPosition.custom);
        assertFalse(settings.progressPosition.custom);
    }

    @Test
    public void invalidSettingsFallBackAndCustomPositionsAreClamped() {
        SharedPreferences preferences = Robolectric.buildActivity(android.app.Activity.class)
            .setup().get().getSharedPreferences("Settings", 0);
        preferences.edit()
            .putString(ReaderStatusSettings.KEY_ENABLED, "yes")
            .putInt(ReaderStatusSettings.KEY_IDLE_DELAY, 4)
            .putInt(ReaderStatusSettings.KEY_STYLE, 9)
            .putInt(ReaderStatusSettings.KEY_BATTERY_FORMAT, -1)
            .putFloat(ReaderStatusSettings.KEY_TEXT_SIZE, Float.NaN)
            .putBoolean(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_custom"), true)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_x"), 1.5f)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_y"), -0.25f)
            .apply();

        ReaderStatusSettings settings = ReaderStatusSettings.load(preferences);

        assertTrue(settings.enabled);
        assertEquals(ReaderStatusSettings.DEFAULT_IDLE_DELAY_SECONDS, settings.idleDelaySeconds);
        assertEquals(ReaderStatusSettings.STYLE_BACKED, settings.style);
        assertEquals(ReaderStatusSettings.BATTERY_ICON_AND_PERCENT, settings.batteryFormat);
        assertEquals(ReaderStatusSettings.DEFAULT_TEXT_SIZE_SP, settings.textSizeSp, 0f);
        assertTrue(settings.timePosition.custom);
        assertEquals(1f, settings.timePosition.x, 0f);
        assertEquals(0f, settings.timePosition.y, 0f);
    }

    @Test
    public void malformedCustomPositionRevertsToDynamicDefault() {
        SharedPreferences preferences = Robolectric.buildActivity(android.app.Activity.class)
            .setup().get().getSharedPreferences("Settings", 0);
        preferences.edit()
            .putBoolean(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_custom"), true)
            .putString(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_x"), "bad")
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_y"), 0.2f)
            .apply();

        ReaderStatusSettings settings = ReaderStatusSettings.load(preferences);

        assertFalse(settings.progressPosition.custom);
    }
}
