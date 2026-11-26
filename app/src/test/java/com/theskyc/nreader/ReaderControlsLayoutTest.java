package com.theskyc.nreader;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class ReaderControlsLayoutTest {
    @Test
    public void controlsFitNarrowScreenInBothDirections() {
        ZoomActivity activity = Robolectric.buildActivity(ZoomActivity.class).get();
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            View controls = LayoutInflater.from(activity).inflate(R.layout.zoom_manager, null, false);
            controls.setLayoutDirection(direction);
            int[] ids = {R.id.prev, R.id.next, R.id.reader_page_preview, R.id.reader_auto_scroll, R.id.reader_settings};
            for (int id : ids) controls.findViewById(id).setVisibility(View.VISIBLE);
            TextView pages = controls.findViewById(R.id.pages);
            pages.setText("999 / 999");
            controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            controls.layout(0, 0, width, controls.getMeasuredHeight());
            assertTrue(pages.getWidth() > 0);
            for (int id : ids) {
                View button = controls.findViewById(id);
                assertTrue(button.getWidth() >= Math.round(48 * density));
                assertTrue(button.getHeight() >= Math.round(48 * density));
                assertTrue(button.getLeft() >= 0 && button.getRight() <= width);
            }
        }
    }

    @Test
    public void toolsMoveBelowNavigationOnlyWhenThePageLabelNeedsSpace() {
        ZoomActivity activity = Robolectric.buildActivity(ZoomActivity.class).get();
        float density = activity.getResources().getDisplayMetrics().density;
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            View controls = LayoutInflater.from(activity).inflate(R.layout.zoom_manager, null, false);
            controls.setLayoutDirection(direction);
            int[] toolIds = {R.id.reader_page_preview, R.id.reader_auto_scroll, R.id.reader_settings};
            for (int id : toolIds) controls.findViewById(id).setVisibility(View.VISIBLE);
            TextView pages = controls.findViewById(R.id.pages);
            pages.setText("999 / 999");
            pages.setMinWidth(Math.round(140 * density));
            LinearLayout row = (LinearLayout) controls.findViewById(R.id.reader_navigation_group).getParent();
            View navigation = controls.findViewById(R.id.reader_navigation_group);
            View tools = controls.findViewById(R.id.reader_tool_group);

            measure(controls, Math.round(320 * density));
            assertEquals(LinearLayout.VERTICAL, row.getOrientation());
            assertTrue(tools.getTop() >= navigation.getBottom());
            assertTrue(pages.getWidth() >= pages.getMinWidth());

            measure(controls, Math.round(480 * density));
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            assertEquals(navigation.getTop(), tools.getTop());
            assertTrue(pages.getWidth() >= pages.getMinWidth());

            for (int id : toolIds) controls.findViewById(id).setVisibility(View.GONE);
            measure(controls, Math.round(320 * density));
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            assertEquals(0, tools.getWidth());
            assertTrue(pages.getWidth() >= pages.getMinWidth());
        }
    }

    private static void measure(View controls, int width) {
        controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        controls.layout(0, 0, width, controls.getMeasuredHeight());
    }
}
