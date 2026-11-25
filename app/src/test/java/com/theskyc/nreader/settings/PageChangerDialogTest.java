package com.theskyc.nreader.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class PageChangerDialogTest {
    @Test
    public void pageControlsFitNarrowDialogWithLargeTextInBothDirections() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        ContextThemeWrapper context = new ContextThemeWrapper(
            activity.createConfigurationContext(configuration), R.style.AppTheme_Material3);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(264 * density);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            View layout = LayoutInflater.from(context).inflate(R.layout.page_changer, null, false);
            layout.setLayoutDirection(direction);
            ((TextView) layout.findViewById(R.id.edit_page)).setText("9999");
            ((TextView) layout.findViewById(R.id.page)).setText("9999");
            layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            layout.layout(0, 0, width, layout.getMeasuredHeight());
            for (int id : new int[]{R.id.prev, R.id.next}) {
                View button = layout.findViewById(id);
                assertTrue(button.getWidth() >= Math.round(48 * density));
                assertTrue(button.getHeight() >= Math.round(48 * density));
            }
            for (int id : new int[]{R.id.edit_page, R.id.page}) {
                TextView value = layout.findViewById(id);
                assertTrue(value.getWidth() > value.getCompoundPaddingLeft() + value.getCompoundPaddingRight());
                assertTrue(value.getLayout().getLineWidth(0) <= value.getWidth()
                    - value.getCompoundPaddingLeft() - value.getCompoundPaddingRight());
            }
        }
    }

    @Test
    public void controlsUseTheDialogTitleAsTheirAccessibleLabel() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Material3);
        for (int title : new int[]{R.string.change_page, R.string.set_minimum_count}) {
            DefaultDialogs.pageChangerDialog(new DefaultDialogs.Builder(activity).setTitle(title));
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText value = dialog.findViewById(R.id.edit_page);
            assertEquals(activity.getText(title), value.getHint());
            assertEquals(activity.getText(title), dialog.findViewById(R.id.seekBar).getContentDescription());
            dialog.dismiss();
        }
    }

    @Test
    public void buttonsAndTypedPageStillSelectTheRequestedPage() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme_Material3);
        AtomicInteger selected = new AtomicInteger();
        DefaultDialogs.pageChangerDialog(new DefaultDialogs.Builder(activity)
            .setMin(1).setMax(20).setActual(4)
            .setDialogs(new DefaultDialogs.CustomDialogResults() {
                @Override
                public void positive(int page) {
                    selected.set(page);
                }
            }));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText page = dialog.findViewById(R.id.edit_page);
        dialog.findViewById(R.id.next).performClick();
        assertEquals("5", page.getText().toString());
        dialog.findViewById(R.id.prev).performClick();
        assertEquals("4", page.getText().toString());
        page.setText("17");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(17, selected.get());
    }
}
