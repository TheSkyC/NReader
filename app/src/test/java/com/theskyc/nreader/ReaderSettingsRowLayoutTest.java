package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
public class ReaderSettingsRowLayoutTest {
    @Test
    public void idleDelayChoicesFitAndRemainSelectableAtLargeText() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("zh", "CN"), new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                R.style.AppTheme_Material3);
            View sheet = LayoutInflater.from(context).inflate(R.layout.reader_status_settings_sheet, null);
            ChipGroup group = sheet.findViewById(R.id.reader_status_idle_delay);
            group.setLayoutDirection(configuration.getLayoutDirection());
            int width = Math.round(232 * context.getResources().getDisplayMetrics().density);
            group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            group.layout(0, 0, width, group.getMeasuredHeight());
            int[] options = {R.id.reader_status_delay_2, R.id.reader_status_delay_3,
                R.id.reader_status_delay_5, R.id.reader_status_delay_10};
            for (int id : options) {
                Chip chip = group.findViewById(id);
                assertTrue(chip.getLeft() >= 0 && chip.getRight() <= group.getWidth());
                assertEquals(0, chip.getLayout().getEllipsisCount(0));
                assertTrue(chip.getPaint().measureText(chip.getText().toString())
                    <= chip.getLayout().getWidth());
                chip.performClick();
                assertEquals(id, group.getCheckedChipId());
            }
        }
    }

    @Test
    public void statusOptionsHaveAccessibleTouchTargets() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        Context context = new ContextThemeWrapper(activity, R.style.AppTheme_Material3);
        View sheet = LayoutInflater.from(context).inflate(R.layout.reader_status_settings_sheet, null);
        int[] options = {
            R.id.reader_status_battery_both, R.id.reader_status_battery_icon_only,
            R.id.reader_status_battery_percent_only, R.id.reader_status_progress_pages,
            R.id.reader_status_progress_percent
        };
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(232 * density);
        for (int id : options) {
            MaterialRadioButton option = sheet.findViewById(id);
            option.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(option.getMeasuredHeight() >= Math.round(48 * density));
            option.performClick();
            assertTrue(option.isChecked());
        }
    }

    @Test
    public void readerSwitchesRemainUsableAtLargeText() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        int[][] sheets = {
            {R.layout.reader_settings_sheet, R.id.reader_auto_crop, R.id.reader_volume_turn,
                R.id.reader_instant_turn},
            {R.layout.reader_status_settings_sheet, R.id.reader_status_enabled,
                R.id.reader_status_time_enabled, R.id.reader_status_battery_enabled,
                R.id.reader_status_progress_enabled, R.id.reader_status_time_idle,
                R.id.reader_status_battery_idle, R.id.reader_status_progress_idle}
        };
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("zh", "CN"), new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                R.style.AppTheme_Material3);
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(232 * density);
            for (int[] entry : sheets) {
                View sheet = LayoutInflater.from(context).inflate(entry[0], null);
                sheet.setLayoutDirection(configuration.getLayoutDirection());
                for (int index = 1; index < entry.length; index++) {
                    MaterialSwitch control = sheet.findViewById(entry[index]);
                    control.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    control.layout(0, 0, width, control.getMeasuredHeight());
                    assertTrue(control.getHeight() >= Math.round(48 * density));
                    assertTrue(control.getHeight() >= control.getLayout().getHeight());
                    assertEquals(0, control.getLayout().getEllipsisCount(control.getLineCount() - 1));
                    boolean checked = control.isChecked();
                    control.performClick();
                    assertEquals(!checked, control.isChecked());
                }
            }
        }
    }

    @Test
    public void choicesKeepLabelsAndValuesReadableAtLargeText() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        int[][] choices = {
            {R.id.reader_direction_toggle, R.id.reader_direction_selected, R.string.reader_continuous_vertical},
            {R.id.reader_spread_mode_toggle, R.id.reader_spread_mode_selected, R.string.reader_spread_single},
            {R.id.reader_orientation_toggle, R.id.reader_orientation_selected, R.string.reader_orientation_system},
            {R.id.reader_scale_toggle, R.id.reader_scale_selected, R.string.reader_scale_smart},
            {R.id.reader_background_toggle, R.id.reader_background_selected, R.string.reader_background_auto},
            {R.id.reader_filter_toggle, R.id.reader_filter_selected, R.string.reader_filter_none},
            {R.id.reader_tap_action_toggle, R.id.reader_tap_action_selected, R.string.reader_tap_action_edge}
        };
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("zh", "CN"), new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                R.style.AppTheme_Material3);
            View sheet = LayoutInflater.from(context).inflate(R.layout.reader_settings_sheet, null);
            sheet.setLayoutDirection(configuration.getLayoutDirection());
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(232 * density);
            for (int[] choice : choices) {
                LinearLayout row = sheet.findViewById(choice[0]);
                TextView value = sheet.findViewById(choice[1]);
                value.setText(choice[2]);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight());

                LinearLayout textGroup = (LinearLayout) value.getParent();
                TextView label = (TextView) textGroup.getChildAt(0);
                View arrow = row.getChildAt(1);
                assertTrue(row.isClickable());
                assertTrue(row.getHeight() >= Math.round(48 * density));
                assertEquals(textGroup.getWidth(), label.getWidth());
                assertEquals(textGroup.getWidth(), value.getWidth());
                assertTrue(value.getTop() >= label.getBottom());
                assertTrue(label.getHeight() >= label.getLayout().getHeight());
                assertTrue(value.getHeight() >= value.getLayout().getHeight());
                assertEquals(0, label.getLayout().getEllipsisCount(label.getLineCount() - 1));
                assertEquals(0, value.getLayout().getEllipsisCount(value.getLineCount() - 1));
                assertTrue(textGroup.getRight() <= arrow.getLeft()
                    || arrow.getRight() <= textGroup.getLeft());
            }
        }
    }
}
