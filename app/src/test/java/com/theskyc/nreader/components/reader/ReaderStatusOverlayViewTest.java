// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
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

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, application = Application.class)
public class ReaderStatusOverlayViewTest {
    @Test
    public void statusItemsHaveMatchingAdaptiveHeightsAcrossFormatsAndFontScales() {
        Activity activity = themedActivity();
        SharedPreferences preferences = activity.getSharedPreferences("Settings", 0);
        for (float fontScale : new float[]{1f, 2f}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.fontScale = fontScale;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                com.theskyc.nreader.R.style.AppTheme_Material3);
            for (float textSize : new float[]{10f, 12f, 18f}) {
                for (int style : new int[]{ReaderStatusSettings.STYLE_BACKED, ReaderStatusSettings.STYLE_MINIMAL}) {
                    int previousHeight = -1;
                    for (int format : new int[]{ReaderStatusSettings.BATTERY_ICON_AND_PERCENT,
                        ReaderStatusSettings.BATTERY_ICON_ONLY, ReaderStatusSettings.BATTERY_PERCENT_ONLY}) {
                        preferences.edit().putFloat(ReaderStatusSettings.KEY_TEXT_SIZE, textSize)
                            .putInt(ReaderStatusSettings.KEY_STYLE, style)
                            .putInt(ReaderStatusSettings.KEY_BATTERY_FORMAT, format).apply();
                        ReaderStatusOverlayView view = new ReaderStatusOverlayView(context);
                        view.setTimeValue("21:38");
                        view.setBatteryValue(76, false);
                        view.setProgressValue(12, 48);
                        view.setEditorMode(true, null);
                        layout(view, 1080, 1920);

                        View time = view.getChildAt(ReaderStatusSettings.ITEM_TIME);
                        LinearLayout battery = (LinearLayout) view.getChildAt(ReaderStatusSettings.ITEM_BATTERY);
                        View progress = view.getChildAt(ReaderStatusSettings.ITEM_PROGRESS);
                        assertEquals(time.getHeight(), battery.getHeight());
                        assertEquals(time.getHeight(), progress.getHeight());
                        assertEquals(time.getY(), battery.getY(), 0f);
                        if (previousHeight >= 0) assertEquals(previousHeight, time.getHeight());
                        previousHeight = time.getHeight();
                        for (int child = 0; child < battery.getChildCount(); child++) {
                            View content = battery.getChildAt(child);
                            if (content.getVisibility() == View.GONE) continue;
                            assertTrue(content.getTop() >= battery.getPaddingTop());
                            assertTrue(content.getBottom() <= battery.getHeight() - battery.getPaddingBottom());
                            assertEquals(battery.getHeight() / 2f,
                                (content.getTop() + content.getBottom()) / 2f, 1f);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void edgeSettingChangesPlacementWithoutLosingCustomCoordinates() {
        Activity activity = themedActivity();
        android.content.SharedPreferences preferences = activity.getSharedPreferences("Settings", 0);
        preferences.edit()
            .putBoolean(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_custom"), true)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_x"), 1f)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_y"), 1f)
            .apply();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        view.setWindowInsets(Insets.of(0, 100, 40, 80), Insets.of(0, 30, 0, 0),
            Insets.NONE, Collections.emptyList());
        view.setTimeValue("21:38");
        view.setBatteryValue(76, false);
        view.setProgressValue(12, 48);
        view.setHostState(true, false, false, false, false, false);
        layout(view, 1080, 1920);
        int edge = dp(activity, 10);
        View time = view.getChildAt(ReaderStatusSettings.ITEM_TIME);
        View progress = view.getChildAt(ReaderStatusSettings.ITEM_PROGRESS);
        assertEquals(30 + edge, time.getY(), 0.01f);
        assertEquals(1920 - edge, progress.getY() + progress.getHeight(), 0.01f);

        preferences.edit().putBoolean(ReaderStatusSettings.KEY_NEAR_EDGES, false).apply();
        view.setSettings(ReaderStatusSettings.load(preferences));
        layout(view, 1080, 1920);
        assertEquals(100 + edge, time.getY(), 0.01f);
        assertEquals(1920 - 80 - edge, progress.getY() + progress.getHeight(), 0.01f);
        assertEquals(1080 - 40 - edge, progress.getX() + progress.getWidth(), 0.01f);
        assertEquals(1f, preferences.getFloat(
            ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_PROGRESS, "position_y"), -1f), 0f);

        preferences.edit().putBoolean(ReaderStatusSettings.KEY_NEAR_EDGES, true).apply();
        view.setSettings(ReaderStatusSettings.load(preferences));
        view.setWindowInsets(Insets.of(70, 0, 40, 0), Insets.of(70, 0, 0, 0),
            Insets.NONE, Collections.emptyList());
        layout(view, 1920, 1080);
        assertEquals(edge, time.getY(), 0.01f);
        assertTrue(time.getX() >= 70 + edge);
    }

    @Test
    public void editorUsesScaledReaderInsetsForSelectedSetting() {
        Activity activity = themedActivity();
        android.content.SharedPreferences preferences = activity.getSharedPreferences("Settings", 0);
        preferences.edit().putBoolean(ReaderStatusSettings.KEY_NEAR_EDGES, false).apply();
        ReaderStatusOverlayView reader = new ReaderStatusOverlayView(activity);
        reader.setWindowInsets(Insets.of(0, 100, 0, 80), Insets.of(0, 30, 0, 0),
            Insets.NONE, Collections.emptyList());
        layout(reader, 1000, 2000);
        ReaderStatusOverlayView preview = new ReaderStatusOverlayView(activity);
        reader.copyPreviewStateTo(preview, null);
        layout(preview, 500, 1000);
        layout(preview, 500, 1000);
        assertEquals(50 + dp(activity, 10),
            preview.getChildAt(ReaderStatusSettings.ITEM_TIME).getY(), 0.01f);
    }

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
    public void stackedDefaultItemsStaySeparatedBelowACutout() {
        Activity activity = themedActivity();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        int width = dp(activity, 50);
        int cutoutBottom = dp(activity, 55);
        Insets cutout = Insets.of(0, cutoutBottom, 0, 0);
        view.setWindowInsets(cutout, cutout, Insets.NONE,
            Collections.singletonList(new Rect(width / 2, 0, width, cutoutBottom)));
        view.setTimeValue("21:38");
        view.setBatteryValue(76, false);
        view.setHostState(true, false, false, false, false, false);
        layout(view, width, dp(activity, 280));

        View time = view.getChildAt(ReaderStatusSettings.ITEM_TIME);
        View battery = view.getChildAt(ReaderStatusSettings.ITEM_BATTERY);
        assertEquals(cutoutBottom, time.getY(), 0.01f);
        assertTrue(battery.getY() >= time.getY() + time.getHeight() + dp(activity, 4));
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
    public void savedCustomPositionIsUsedInTheEditorPreview() {
        Activity activity = themedActivity();
        activity.getSharedPreferences("Settings", 0).edit()
            .putBoolean(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_custom"), true)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_x"), 0.25f)
            .putFloat(ReaderStatusSettings.preferenceKey(ReaderStatusSettings.ITEM_TIME, "position_y"), 0.75f)
            .apply();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        activity.setContentView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.setTimeValue("21:38");
        view.setBatteryValue(76, false);
        view.setProgressValue(12, 48);
        view.setEditorMode(true, null);
        layout(view, 1080, 1920);

        float[] position = view.normalizedPositionFor(ReaderStatusSettings.ITEM_TIME);

        assertEquals(0.25f, position[0], 0.02f);
        assertEquals(0.75f, position[1], 0.02f);
    }

    @Test
    public void timeValueRefreshMakesTheEnabledItemVisible() {
        Activity activity = themedActivity();
        ReaderStatusOverlayView view = new ReaderStatusOverlayView(activity);
        view.setHostState(true, false, false, false, false, false);

        assertEquals(View.INVISIBLE, view.getChildAt(ReaderStatusSettings.ITEM_TIME).getVisibility());

        view.setTimeValue("21:38");

        assertEquals(View.VISIBLE, view.getChildAt(ReaderStatusSettings.ITEM_TIME).getVisibility());
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
