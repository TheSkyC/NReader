package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.slider.Slider;
import com.theskyc.nreader.components.views.MaterialSliderPreference;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class MaterialSliderPreferenceTest {
    @Test
    public void readerZoomSliderPreservesRangeAndPersistsTouchChanges() {
        Application application = RuntimeEnvironment.getApplication();
        String key = application.getString(R.string.preference_key_default_zoom);
        SharedPreferences preferences = application.getSharedPreferences("Settings", 0);
        boolean hadValue = preferences.contains(key);
        int previousValue = preferences.getInt(key, 100);
        preferences.edit().remove(key).commit();
        Global.initFromShared(application);

        try (ActivityController<SettingsActivity> root = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            PreferenceScreen reader = root.get().fragment.findPreference("settings_screen_reader");
            reader.performClick();
            Intent intent = Shadows.shadowOf(root.get()).getNextStartedActivity();
            try (ActivityController<SettingsActivity> child = Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
                RecyclerView list = child.get().fragment.getListView();
                int width = Math.round(320 * child.get().getResources().getDisplayMetrics().density);
                int height = Math.round(640 * child.get().getResources().getDisplayMetrics().density);
                list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, width, height);
                int position = ((PreferenceGroupAdapter) list.getAdapter()).getPreferenceAdapterPosition(key);
                list.scrollToPosition(position);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                list.layout(0, 0, width, height);

                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
                assertNotNull(holder);
                assertTrue(child.get().fragment.findPreference(key) instanceof MaterialSliderPreference);
                Slider slider = holder.itemView.findViewById(R.id.preference_slider);
                assertNotNull(slider);
                assertEquals(100f, slider.getValueFrom(), 0f);
                assertEquals(400f, slider.getValueTo(), 0f);
                assertEquals(10f, slider.getStepSize(), 0f);
                assertEquals(100f, slider.getValue(), 0f);

                long downTime = SystemClock.uptimeMillis();
                float centerY = slider.getHeight() / 2f;
                float startX = slider.getWidth() * 0.08f;
                float targetX = slider.getWidth() * 0.57f;
                dispatchTouch(slider, downTime, downTime, MotionEvent.ACTION_DOWN, startX, centerY);
                dispatchTouch(slider, downTime, downTime + 100, MotionEvent.ACTION_MOVE, targetX, centerY);
                dispatchTouch(slider, downTime, downTime + 200, MotionEvent.ACTION_UP, targetX, centerY);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(270f, slider.getValue(), 0f);
                assertEquals(270, preferences.getInt(key, -1));
                TextView value = holder.itemView.findViewById(R.id.preference_slider_value);
                assertEquals("270", value.getText().toString());
            }
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadValue) editor.putInt(key, previousValue);
            else editor.remove(key);
            editor.commit();
        }
    }

    private static void dispatchTouch(Slider slider, long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        slider.dispatchTouchEvent(event);
        event.recycle();
    }
}
