package com.theskyc.nreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.core.graphics.ColorUtils;
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
    @Config(qualifiers = "notnight")
    public void switchStatesRemainDistinctInLightTheme() {
        assertSwitchContrast(R.style.AppTheme_Material3);
    }

    @Test
    @Config(qualifiers = "night")
    public void switchStatesRemainDistinctInDarkTheme() {
        assertSwitchContrast(R.style.AppTheme_Material3);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void switchStatesRemainDistinctInBlackTheme() {
        assertSwitchContrast(R.style.AppTheme_Material3_Black);
    }

    private void assertSwitchContrast(int theme) {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
        MaterialSwitch switchView = (MaterialSwitch) LayoutInflater.from(context)
            .inflate(R.layout.preference_material_switch, null, false);
        ColorStateList outline = switchView.getTrackDecorationTintList();
        assertNotNull(outline);
        int enabled = outline.getColorForState(new int[]{android.R.attr.state_enabled}, 0);
        int disabled = outline.getColorForState(new int[]{-android.R.attr.state_enabled}, 0);
        TypedValue background = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(android.R.attr.colorBackground, background, true));
        int backgroundColor = background.data;
        assertTrue(ColorUtils.calculateContrast(enabled, backgroundColor) >= 3.0);
        int disabledOnBackground = ColorUtils.compositeColors(disabled, backgroundColor);
        assertTrue(ColorUtils.calculateContrast(disabledOnBackground, backgroundColor)
            < ColorUtils.calculateContrast(enabled, backgroundColor));
    }

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
