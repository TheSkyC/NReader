package com.theskyc.nreader;

import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.core.graphics.ColorUtils;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class UpdateCenterThemeTest {
    private SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0);
    }

    @After
    public void clearBlackTheme() {
        preferences().edit().remove(RuntimeEnvironment.getApplication()
            .getString(R.string.preference_key_black_theme)).commit();
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightThemeUsesReadableMaterial3Colors() {
        assertTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void darkThemeUsesReadableMaterial3Colors() {
        assertTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void blackThemeRemainsMaterial3InLightMode() {
        assertTheme(true);
    }

    private void assertTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        UpdateCenterActivity activity = Robolectric.buildActivity(UpdateCenterActivity.class).get();
        TypedValue material = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(com.google.android.material.R.attr.isMaterial3Theme, material, true));
        assertTrue(material.data != 0);
        int primary = color(activity, R.attr.colorPrimary);
        int onPrimary = color(activity, com.google.android.material.R.attr.colorOnPrimary);
        int surface = color(activity, com.google.android.material.R.attr.colorSurface);
        int onSurface = color(activity, com.google.android.material.R.attr.colorOnSurface);
        assertTrue(ColorUtils.calculateContrast(onPrimary, primary) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(onSurface, surface) >= 4.5);
        if (black) assertEquals(Color.BLACK, surface);
    }

    private int color(UpdateCenterActivity activity, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(attribute, value, true));
        return value.data;
    }
}
