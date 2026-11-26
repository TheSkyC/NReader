package com.theskyc.nreader.components.activities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

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
}
