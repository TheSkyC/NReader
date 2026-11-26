// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class ReaderStatusOverlayViewTest {
    @Test
    public void defaultItemsFitTheSafeAreaAndDoNotOverlap() {
        Activity activity = themedActivity();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        activity.setContentView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.setSafeInsets(Insets.of(24, 32, 18, 28));
        view.setTimeValue("21:38");
        view.setBatteryValue(76, false);
        view.setProgressValue(12, 48);
        view.setHostState(true, false, false, false, false, false);
        layout(view, 1080, 1920);

        View time = view.getChildAt(ReaderStatusSettings.ITEM_TIME);
        View battery = view.getChildAt(ReaderStatusSettings.ITEM_BATTERY);
        View progress = view.getChildAt(ReaderStatusSettings.ITEM_PROGRESS);
        assertEquals(View.VISIBLE, time.getVisibility());
        assertEquals(View.VISIBLE, battery.getVisibility());
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertTrue(time.getX() + time.getWidth() < battery.getX());
        assertTrue(progress.getX() + progress.getWidth() <= 1080 - 18 - dp(activity, 10));
        assertTrue(progress.getY() + progress.getHeight() <= 1920 - 28 - dp(activity, 10));
        assertFalse(view.hasOverlappingItems());
    }

    @Test
    public void invalidProgressAndBatteryAreNeverPresentedAsZero() {
        Activity activity = themedActivity();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        view.setTimeValue("21:38");
        view.setBatteryValue(-1, false);
        view.setProgressValue(0, 0);
        view.setHostState(true, false, false, false, false, false);

        assertEquals(View.INVISIBLE, view.getChildAt(ReaderStatusSettings.ITEM_BATTERY).getVisibility());
        assertEquals(View.INVISIBLE, view.getChildAt(ReaderStatusSettings.ITEM_PROGRESS).getVisibility());
        assertEquals("", ((TextView) ((LinearLayout) view.getChildAt(ReaderStatusSettings.ITEM_PROGRESS))
            .getChildAt(0)).getText().toString());
    }

    @Test
    public void controlsAndModalSurfacesSuppressEveryItem() {
        Activity activity = themedActivity();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        view.setTimeValue("21:38");
        view.setBatteryValue(60, true);
        view.setProgressValue(3, 8);
        view.setHostState(true, true, false, false, false, false);
        for (int index = 0; index < 3; index++)
            assertEquals(View.INVISIBLE, view.getChildAt(index).getVisibility());
        view.setHostState(true, false, true, false, false, false);
        for (int index = 0; index < 3; index++)
            assertEquals(View.INVISIBLE, view.getChildAt(index).getVisibility());
    }

    @Test
    public void statusItemsPassReaderTouchesThroughUntilEditorModeIsEnabled() {
        Activity activity = themedActivity();
        FrameLayout root = new FrameLayout(activity);
        View readerSurface = new View(activity);
        int[] deliveredUps = {0};
        readerSurface.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) deliveredUps[0]++;
            return true;
        });
        root.addView(readerSurface, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ReaderStatusOverlayView overlay = new ReaderStatusOverlayView(activity);
        overlay.setTimeValue("21:38");
        overlay.setBatteryValue(60, false);
        overlay.setProgressValue(3, 8);
        overlay.setHostState(true, false, false, false, false, false);
        root.addView(overlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        activity.setContentView(root);
        layout(root, 1080, 1920);

        View time = overlay.getChildAt(ReaderStatusSettings.ITEM_TIME);
        float x = time.getX() + time.getWidth() / 2f;
        float y = time.getY() + time.getHeight() / 2f;
        dispatch(root, MotionEvent.ACTION_DOWN, x, y);
        dispatch(root, MotionEvent.ACTION_UP, x, y);

        assertEquals(1, deliveredUps[0]);
        overlay.setEditorMode(true, null);
        dispatch(root, MotionEvent.ACTION_DOWN, x, y);
        dispatch(root, MotionEvent.ACTION_UP, x, y);
        assertEquals(1, deliveredUps[0]);
    }

    private Activity themedActivity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.theskyc.nreader.R.style.AppTheme_Material3);
        return activity;
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void dispatch(View root, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
    }
}
