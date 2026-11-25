package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

import com.theskyc.nreader.settings.Global;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ThemeIconTintTest {
    @After
    public void clearBlackTheme() {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("Settings", 0).edit()
            .remove(app.getString(R.string.preference_key_black_theme)).commit();
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightThemeUsesDarkSurfaceIcon() {
        assertTint(false, Color.BLACK);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void blackThemeUsesLightSurfaceIconInLightSystemMode() {
        assertTint(true, Color.WHITE);
    }

    private void assertTint(boolean black, int expected) {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        FavoriteActivity activity = Robolectric.buildActivity(FavoriteActivity.class).get();
        RecordingDrawable drawable = new RecordingDrawable();
        Global.setTint(activity, drawable);
        assertEquals(expected, drawable.tint);
    }

    private static class RecordingDrawable extends ColorDrawable {
        int tint;

        @Override
        public void setTint(int color) {
            tint = color;
        }
    }
}
