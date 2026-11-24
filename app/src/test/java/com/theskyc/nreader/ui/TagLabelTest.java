package com.theskyc.nreader.ui;

import android.content.res.Configuration;
import android.view.View;

import com.theskyc.nreader.R;
import com.theskyc.nreader.settings.TagDisplayMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class TagLabelTest {
    @Before public void resetMode() {
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit()
            .remove(RuntimeEnvironment.getApplication().getString(R.string.preference_key_tag_display_format)).commit();
    }

    @Test public void defaultDisplaysTranslationAndSwitchingModeDisplaysBoth() {
        View view = view(Locale.US, View.LAYOUT_DIRECTION_LTR);
        assertEquals("Display text", TagLabel.format(view, "canonical-tag", " Display text "));
        setMode(TagDisplayMode.BILINGUAL);
        assertEquals("Display text (canonical-tag)", TagLabel.format(view, "canonical-tag", "Display text"));
        setMode(TagDisplayMode.TRANSLATED_ONLY);
        assertEquals("Display text", TagLabel.format(view, "canonical-tag", "Display text"));
    }

    @Test public void missingTranslationsFallBackInBothModes() {
        View view = view(Locale.US, View.LAYOUT_DIRECTION_LTR);
        for (String mode : new String[]{TagDisplayMode.TRANSLATED_ONLY, TagDisplayMode.BILINGUAL}) {
            setMode(mode);
            assertEquals("canonical", TagLabel.format(view, "canonical", null));
            assertEquals("canonical", TagLabel.format(view, "canonical", "  "));
            assertEquals("canonical", TagLabel.format(view, "canonical", "canonical"));
        }
    }

    @Test public void invalidStoredModeDefaultsToTranslated() {
        View view = view(Locale.US, View.LAYOUT_DIRECTION_LTR);
        setMode("invalid");
        assertEquals("translation", TagLabel.format(view, "canonical", "translation"));
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit()
            .putInt(RuntimeEnvironment.getApplication().getString(R.string.preference_key_tag_display_format), 1).commit();
        assertEquals("translation", TagLabel.format(view, "canonical", "translation"));
    }

    @Test public void explicitDirectionsCannotSpillIntoTheOtherLabel() {
        setMode(TagDisplayMode.BILINGUAL);
        View view = view(Locale.US, View.LAYOUT_DIRECTION_LTR);
        assertEquals("Display text (canonical-tag)",
            TagLabel.format(view, "canonical-\u202Etag", "Display \u2069\u202Etext"));
        assertEquals("canonical-tag", TagLabel.format(view, "canonical-tag", "\u202E\u2069"));
    }

    @Test public void rightToLeftLabelsPreserveJoiners() {
        View view = view(Locale.forLanguageTag("ar"), View.LAYOUT_DIRECTION_RTL);
        String name = "ن\u200Cظ\u200Dارة";
        String expected = TagLabel.format(view, "glasses", name);
        assertEquals(expected, TagLabel.format(view, "glasses", name + "\u061C\u202C"));
        org.junit.Assert.assertTrue(expected.contains(name));
    }

    @Test public void translationCannotStandInForAMissingCanonicalName() {
        assertEquals("", TagLabel.format(view(Locale.US, View.LAYOUT_DIRECTION_LTR), null, "translation"));
    }

    private View view(Locale locale, int direction) {
        Configuration configuration = new Configuration();
        configuration.setLocale(locale);
        View view = new View(RuntimeEnvironment.getApplication().createConfigurationContext(configuration));
        view.setLayoutDirection(direction);
        return view;
    }

    private void setMode(String mode) {
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit()
            .putString(RuntimeEnvironment.getApplication().getString(R.string.preference_key_tag_display_format), mode).commit();
    }
}
