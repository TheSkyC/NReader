package com.theskyc.nreader;

import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.view.ContextThemeWrapper;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PreferenceTextWrapTest {
    @Test
    public void preferenceTitlesCanWrapAcrossPreferenceTypes() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        ContextThemeWrapper context = new ContextThemeWrapper(activity, R.style.NReaderPreferenceThemeOverlay);

        assertFalse(new Preference(context).isSingleLineTitle());
        assertFalse(new ListPreference(context).isSingleLineTitle());
        assertFalse(new SwitchPreferenceCompat(context).isSingleLineTitle());
    }
}
