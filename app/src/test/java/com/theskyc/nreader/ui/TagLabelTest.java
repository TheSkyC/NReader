package com.theskyc.nreader.ui;

import android.content.res.Configuration;
import android.view.View;

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
    @Test public void explicitDirectionsCannotSpillIntoTheOtherLabel() {
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

    private View view(Locale locale, int direction) {
        Configuration configuration = new Configuration();
        configuration.setLocale(locale);
        View view = new View(RuntimeEnvironment.getApplication().createConfigurationContext(configuration));
        view.setLayoutDirection(direction);
        return view;
    }
}
