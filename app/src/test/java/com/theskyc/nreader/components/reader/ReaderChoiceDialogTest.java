// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.radiobutton.MaterialRadioButton;
import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class ReaderChoiceDialogTest {
    @Test public void wrappedOptionsKeepTheirHeightAndSelectionUpdatesOnlyOnClick() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        Configuration configuration = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme_Material3);
        RadioGroup source = new RadioGroup(context);
        String[] labels = {"Horizontal", "Vertical", "Continuous vertical"};
        for (String label : labels) {
            MaterialRadioButton option = new MaterialRadioButton(context);
            option.setId(View.generateViewId());
            option.setText(label);
            source.addView(option);
        }
        source.check(source.getChildAt(2).getId());
        AlertDialog dialog = ReaderChoiceDialog.show(context, R.string.change_scroll_type, source);
        ScrollView scroll = findScrollView(dialog.getWindow().getDecorView());
        assertNotNull(scroll);
        RadioGroup choices = (RadioGroup) scroll.getChildAt(0);
        int width = Math.round(250 * context.getResources().getDisplayMetrics().density);
        choices.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        choices.layout(0, 0, width, choices.getMeasuredHeight());
        MaterialRadioButton longOption = (MaterialRadioButton) choices.getChildAt(2);
        assertTrue(longOption.isChecked());
        assertEquals(longOption.getId(), choices.getCheckedRadioButtonId());
        assertEquals(0, longOption.getLayout().getEllipsisCount(0));
        assertTrue(longOption.getHeight() >= longOption.getLayout().getHeight());
        choices.getChildAt(0).performClick();
        assertEquals(source.getChildAt(0).getId(), source.getCheckedRadioButtonId());
        assertFalse(dialog.isShowing());

        AlertDialog canceled = ReaderChoiceDialog.show(context, R.string.change_scroll_type, source);
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(source.getChildAt(0).getId(), source.getCheckedRadioButtonId());
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView && ((ScrollView) view).getChildAt(0) instanceof RadioGroup) {
            return (ScrollView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                ScrollView found = findScrollView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
}
