package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.theskyc.nreader.api.local.LocalSortType;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h480dp")
public class LocalSortDialogTest {
    @Test public void optionsScrollAtLargeTextAndSaveOrCancel() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        LocalSortType original = Global.getLocalSortType();
        try (ActivityController<LocalActivity> controller = Robolectric.buildActivity(LocalActivity.class).setup()) {
            LocalActivity activity = controller.get();
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.fontScale = 1.5f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            LocalSortType initial = new LocalSortType(LocalSortType.Type.TITLE, false);
            Global.setLocalSortType(activity, initial);

            AlertDialog dialog = showSortDialog(activity);
            ScrollView scroll = dialog.findViewById(R.id.sort_options_scroll);
            MaterialSwitch descending = dialog.findViewById(R.id.ascending);
            float density = activity.getResources().getDisplayMetrics().density;
            int width = Math.round(density * 240);
            int height = Math.round(density * 120);
            scroll.setSmoothScrollingEnabled(false);
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                scroll.setLayoutDirection(direction);
                scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                scroll.layout(0, 0, width, height);
                scroll.scrollTo(0, 0);
                assertTrue(scroll.canScrollVertically(1));
                scroll.fullScroll(View.FOCUS_DOWN);
                View content = scroll.getChildAt(0);
                assertTrue(descending.getBottom() + content.getTop() <= scroll.getScrollY() + scroll.getHeight());
            }
            ((ChipGroup) dialog.findViewById(R.id.chip_group)).check(R.id.chip6);
            descending.setChecked(true);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(initial, Global.getLocalSortType());

            dialog = showSortDialog(activity);
            ((ChipGroup) dialog.findViewById(R.id.chip_group)).check(R.id.chip6);
            ((MaterialSwitch) dialog.findViewById(R.id.ascending)).setChecked(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(new LocalSortType(LocalSortType.Type.GROUP, true), Global.getLocalSortType());
        } finally {
            Global.setLocalSortType(app, original);
        }
    }

    private static AlertDialog showSortDialog(LocalActivity activity) {
        ReflectionHelpers.callInstanceMethod(activity, "dialogSortType");
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
}
