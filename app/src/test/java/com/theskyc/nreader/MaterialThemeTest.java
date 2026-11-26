package com.theskyc.nreader.components.activities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
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
            .commit();
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
