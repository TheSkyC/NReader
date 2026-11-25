package com.theskyc.nreader;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatActivity;
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

    @Test
    @Config(qualifiers = "notnight")
    public void mainScreenUsesMaterial3InLightMode() {
        assertMainTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void mainScreenUsesMaterial3InDarkMode() {
        assertMainTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void mainScreenKeepsBlackMaterial3ThemeInLightMode() {
        assertMainTheme(true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void accountsUsesMaterial3InLightMode() {
        assertAccountsTheme(false);
    }

    @Test
    @Config(qualifiers = "night")
    public void accountsUsesMaterial3InDarkMode() {
        assertAccountsTheme(false);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void accountsKeepsBlackMaterial3ThemeInLightMode() {
        assertAccountsTheme(true);
    }

    private void assertAccountsTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(AccountsActivity.class).get(), black);
    }

    private void assertMainTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(MainActivity.class).get(), black);
    }

    private void assertTheme(boolean black) {
        Application app = RuntimeEnvironment.getApplication();
        preferences().edit().putBoolean(app.getString(R.string.preference_key_black_theme), black).commit();
        assertColors(Robolectric.buildActivity(UpdateCenterActivity.class).get(), black);
    }

    private void assertColors(AppCompatActivity activity, boolean black) {
        TypedValue material = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(com.google.android.material.R.attr.isMaterial3Theme, material, true));
        assertTrue(material.data != 0);
        int primary = color(activity, R.attr.colorPrimary);
        int onPrimary = color(activity, com.google.android.material.R.attr.colorOnPrimary);
        int surface = color(activity, com.google.android.material.R.attr.colorSurface);
        int onSurface = color(activity, com.google.android.material.R.attr.colorOnSurface);
        int inverseSurface = color(activity, com.google.android.material.R.attr.colorSurfaceInverse);
        int inverseOnSurface = color(activity, com.google.android.material.R.attr.colorOnSurfaceInverse);
        int inversePrimary = color(activity, com.google.android.material.R.attr.colorPrimaryInverse);
        assertTrue(ColorUtils.calculateContrast(onPrimary, primary) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(onSurface, surface) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(inverseOnSurface, inverseSurface) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(inversePrimary, inverseSurface) >= 4.5);
        boolean lightBars = !black && (activity.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        TypedValue systemBars = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, systemBars, true));
        assertEquals(lightBars, systemBars.data != 0);
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowLightNavigationBar, systemBars, true));
        assertEquals(lightBars, systemBars.data != 0);
        if (black) assertEquals(Color.BLACK, surface);
    }

    private int color(AppCompatActivity activity, int attribute) {
        TypedValue value = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(attribute, value, true));
        return value.data;
    }
}
