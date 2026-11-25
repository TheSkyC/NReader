package com.theskyc.nreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
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
public class SettingsSwitchTest {
    @Test
    public void readerSwitchFollowsRowAndPersistsItsValue() {
        Application application = RuntimeEnvironment.getApplication();
        String key = application.getString(R.string.preference_key_change_page_buttons);
        application.getSharedPreferences("Settings", 0).edit().putBoolean(key, false).commit();
        Global.initFromShared(application);
        try (ActivityController<SettingsActivity> root = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            PreferenceScreen screen = root.get().fragment.findPreference("settings_screen_reader");
            screen.performClick();
            Intent intent = Shadows.shadowOf(root.get()).getNextStartedActivity();
            try (ActivityController<SettingsActivity> child = Robolectric.buildActivity(SettingsActivity.class, intent).setup()) {
                RecyclerView list = child.get().fragment.getListView();
                int width = Math.round(320 * child.get().getResources().getDisplayMetrics().density);
                int height = Math.round(640 * child.get().getResources().getDisplayMetrics().density);
                list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, width, height);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                int position = ((PreferenceGroupAdapter) list.getAdapter()).getPreferenceAdapterPosition(key);
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
                assertNotNull(holder);
                MaterialSwitch switchView = holder.itemView.findViewById(androidx.preference.R.id.switchWidget);
                assertNotNull(switchView);
                assertFalse(switchView.isClickable());
                assertFalse(switchView.isChecked());
                assertTrue(holder.itemView.performClick());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                list.layout(0, 0, width, height);
                holder = list.findViewHolderForAdapterPosition(position);
                assertNotNull(holder);
                switchView = holder.itemView.findViewById(androidx.preference.R.id.switchWidget);
                assertTrue(switchView.isChecked());
                assertTrue(application.getSharedPreferences("Settings", 0).getBoolean(key, false));
            }
        }
    }
}
