package com.theskyc.nreader;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainContentStateLayoutTest {
    @Test
    public void contentStateStartsHiddenWithMaterialRetryAction() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Material3);
        View root = LayoutInflater.from(activity).inflate(R.layout.content_main, null, false);

        assertEquals(View.GONE, root.findViewById(R.id.content_state).getVisibility());
        View action = root.findViewById(R.id.content_state_action);
        assertTrue(action instanceof MaterialButton);
        assertEquals(activity.getString(R.string.retry), ((MaterialButton) action).getText().toString());
    }
}
