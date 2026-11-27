package com.theskyc.nreader.components.activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.TypedArray;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;

import com.theskyc.nreader.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class MaterialThemeTest {
    private final Application application = RuntimeEnvironment.getApplication();

    @After
    public void clearThemePreference() {
        application.getSharedPreferences("Settings", 0).edit()
            .remove(application.getString(R.string.preference_key_black_theme))
            .remove(application.getString(R.string.preference_key_theme_select))
            .commit();
    }

    @Test
    public void applicationNightModeMatchesDefaultAndSavedTheme() {
        String key = application.getString(R.string.preference_key_theme_select);
        String[] themes = application.getResources().getStringArray(R.array.theme_data);
        application.getSharedPreferences("Settings", 0).edit().remove(key).commit();
        assertEquals(UiModeManager.MODE_NIGHT_NO, CrashApplication.resolveApplicationNightMode(application));

        for (int index = 0; index < themes.length; index++) {
            application.getSharedPreferences("Settings", 0).edit().putString(key, themes[index]).commit();
            assertEquals(index == 0 ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES,
                CrashApplication.resolveApplicationNightMode(application));
        }
    }

    @Test
    public void standardThemeAllowsSystemDynamicColors() {
        assertTrue(CrashApplication.shouldApplyDynamicColors(application));
    }

    @Test
    public void blackThemeKeepsItsOwnMaterial3Palette() {
        application.getSharedPreferences("Settings", 0).edit()
            .putBoolean(application.getString(R.string.preference_key_black_theme), true)
            .commit();
        assertFalse(CrashApplication.shouldApplyDynamicColors(application));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightThemeUsesProgressiveSurfaceContainers() {
        assertSurfaceContainerOrder(R.style.AppTheme_Material3, false);
    }

    @Test
    @Config(qualifiers = "night")
    public void darkThemeUsesProgressiveSurfaceContainers() {
        assertSurfaceContainerOrder(R.style.AppTheme_Material3, true);
    }

    @Test
    public void blackThemeUsesProgressiveSurfaceContainers() {
        assertSurfaceContainerOrder(R.style.AppTheme_Material3_Black, true);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightThemeDistinguishesControlsFromDividers() {
        assertOutlineAndTextHierarchy(R.style.AppTheme_Material3);
    }

    @Test
    @Config(qualifiers = "night")
    public void darkThemeDistinguishesControlsFromDividers() {
        assertOutlineAndTextHierarchy(R.style.AppTheme_Material3);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void blackThemeDistinguishesControlsFromDividers() {
        assertOutlineAndTextHierarchy(R.style.AppTheme_Material3_Black);
    }

    private void assertOutlineAndTextHierarchy(int themeResource) {
        Context context = new ContextThemeWrapper(application, themeResource);
        int[] attributes = {
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            com.google.android.material.R.attr.colorOutline,
            com.google.android.material.R.attr.colorOutlineVariant,
            com.google.android.material.R.attr.colorOnSurface,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            android.R.attr.textColorSecondary,
        };
        TypedArray values = context.obtainStyledAttributes(attributes);
        int surface = values.getColor(0, 0);
        int container = values.getColor(1, 0);
        int outline = values.getColor(2, 0);
        int divider = values.getColor(3, 0);
        int primaryText = values.getColor(4, 0);
        int secondaryText = values.getColor(5, 0);
        int platformSecondaryText = values.getColor(6, 0);
        values.recycle();

        assertTrue(ColorUtils.calculateContrast(outline, surface) >= 3.0);
        assertTrue(ColorUtils.calculateContrast(outline, container) >= 3.0);
        assertTrue(ColorUtils.calculateContrast(divider, surface)
            < ColorUtils.calculateContrast(outline, surface));
        assertTrue(ColorUtils.calculateContrast(secondaryText, container) >= 4.5);
        assertTrue(ColorUtils.calculateContrast(secondaryText, surface)
            < ColorUtils.calculateContrast(primaryText, surface));
        assertEquals(secondaryText, platformSecondaryText);
    }

    private void assertSurfaceContainerOrder(int themeResource, boolean dark) {
        Context context = new ContextThemeWrapper(application, themeResource);
        int[] attributes = {
            com.google.android.material.R.attr.colorSurfaceContainerLowest,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            com.google.android.material.R.attr.colorSurfaceContainer,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
        };
        TypedArray values = context.obtainStyledAttributes(attributes);
        double previous = ColorUtils.calculateLuminance(values.getColor(0, 0));
        for (int index = 1; index < attributes.length; index++) {
            double current = ColorUtils.calculateLuminance(values.getColor(index, 0));
            assertTrue(dark ? current > previous : current < previous);
            previous = current;
        }
        values.recycle();
    }
}
