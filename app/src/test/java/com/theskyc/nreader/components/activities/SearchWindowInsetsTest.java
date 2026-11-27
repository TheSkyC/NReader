package com.theskyc.nreader.components.activities;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Application;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.theskyc.nreader.AccountLoginActivity;
import com.theskyc.nreader.GalleryActivity;
import com.theskyc.nreader.TagFilterActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchWindowInsetsTest {
    @Test public void tagResultsAndGalleryResizeForKeyboardAndRestoreSystemBars() {
        Activity[] activities = {
            Robolectric.buildActivity(TagFilterActivity.class).get(),
            Robolectric.buildActivity(GalleryActivity.class).get(),
            Robolectric.buildActivity(AccountLoginActivity.class).get(),
            Robolectric.buildActivity(Activity.class).get()
        };
        WindowInsetsCompat withKeyboard = new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(4, 20, 6, 30))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
            .build();
        WindowInsetsCompat withoutKeyboard = new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(4, 20, 6, 30))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build();
        View root = new View(RuntimeEnvironment.getApplication());
        for (Activity activity : activities) {
            CrashApplication.applyWindowInsets(activity, root, withKeyboard);
            assertEquals(4, root.getPaddingLeft());
            assertEquals(20, root.getPaddingTop());
            assertEquals(6, root.getPaddingRight());
            assertEquals(activity instanceof TagFilterActivity || activity instanceof GalleryActivity
                || activity instanceof AccountLoginActivity ? 300 : 30,
                root.getPaddingBottom());
            CrashApplication.applyWindowInsets(activity, root, withoutKeyboard);
            assertEquals(30, root.getPaddingBottom());
        }
    }
}
