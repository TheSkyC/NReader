// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.content.SharedPreferences;

import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.SortType;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class GlobalPreferenceValidationTest {
    @Test
    public void invalidOrdinalsAndTypesUseSafeDefaults() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("preference_validation_test", 0);
        preferences.edit().clear().putInt("language", 999).putInt("sort", -1)
            .putString("wrong_type", "not-an-integer").putString("wrong_boolean", "not-a-boolean").commit();

        assertEquals(Language.ALL, Global.getEnumPreference(
            preferences, "language", Language.values(), Language.ALL));
        assertEquals(SortType.RECENT_ALL_TIME, Global.getEnumPreference(
            preferences, "sort", SortType.values(), SortType.RECENT_ALL_TIME));
        assertEquals(42, Global.getIntPreference(preferences, "wrong_type", 42));
        org.junit.Assert.assertTrue(Global.getBooleanPreference(preferences, "wrong_boolean", true));
    }
}
