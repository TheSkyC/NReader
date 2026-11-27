// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class ReaderStatusOverlayControllerTest {
    @Test
    public void idleHidesOnlyConfiguredItemsAndActivityRevealsThemAgain() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.theskyc.nreader.R.style.AppTheme_Material3);
        activity.getSharedPreferences("Settings", 0).edit()
            .putInt(ReaderStatusSettings.KEY_IDLE_DELAY, 4).apply();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        activity.setContentView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        layout(view, 1080, 1920);
        view.setSafeInsets(Insets.of(0, 48, 0, 72));
        ReaderStatusOverlayController controller = new ReaderStatusOverlayController(activity, view);
        controller.setProgress(1, 20);
        view.setTimeValue("21:38");
        view.setBatteryValue(44, false);
        controller.onResume();
        controller.setControlsVisible(false);

        assertEquals(View.VISIBLE, view.getChildAt(0).getVisibility());
        assertEquals(View.VISIBLE, view.getChildAt(1).getVisibility());
        assertEquals(View.VISIBLE, view.getChildAt(2).getVisibility());

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
        assertEquals(View.VISIBLE, view.getChildAt(2).getVisibility());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);

        assertEquals(View.VISIBLE, view.getChildAt(0).getVisibility());
        assertEquals(View.VISIBLE, view.getChildAt(1).getVisibility());
        assertEquals(View.INVISIBLE, view.getChildAt(2).getVisibility());

        controller.onReaderActivity();
        assertEquals(View.VISIBLE, view.getChildAt(2).getVisibility());
        controller.onPause();
        assertEquals(View.INVISIBLE, view.getChildAt(0).getVisibility());
        assertEquals(View.INVISIBLE, view.getChildAt(1).getVisibility());
        controller.close();
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
