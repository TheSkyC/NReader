package com.theskyc.nreader.settings;

import android.app.Application;

import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en-rUS")
public class TagLanguageTest {
    @Test public void selectionIsValidatedAndCanFollowAppLanguage() {
        Application app = RuntimeEnvironment.getApplication();
        String key = app.getString(R.string.preference_key_tag_display_language);
        app.getSharedPreferences("Settings", 0).edit().remove(key).commit();

        assertEquals("", TagLanguage.selected(app));
        assertEquals(Locale.US, TagLanguage.current(app));

        app.getSharedPreferences("Settings", 0).edit().putString(key, "not a locale").commit();
        assertEquals("", TagLanguage.selected(app));

        TagLanguage.select(app, "ja");
        assertEquals("ja", TagLanguage.selected(app));
        assertEquals(Locale.forLanguageTag("ja"), TagLanguage.current(app));

        TagLanguage.select(app, "");
        assertEquals("", TagLanguage.selected(app));
    }
}
