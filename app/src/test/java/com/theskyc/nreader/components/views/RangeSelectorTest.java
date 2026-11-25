// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.views;

import android.app.Activity;
import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.Gallery;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RangeSelectorTest {
    @Test
    public void rangeContentCanScrollInAShortWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(264 * density);
        int height = Math.round(200 * density);
        ScrollView content = (ScrollView) LayoutInflater.from(activity)
            .inflate(R.layout.range_selector, null, false);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, width, height);
        assertTrue(content.getChildAt(0).getHeight() > content.getHeight());
        content.scrollTo(0, content.getChildAt(0).getHeight());
        assertTrue(content.getScrollY() > 0);
    }

    @Test
    public void downloadRangeShowsOnlyRangeControlsAndKeepsBothSelectorsFunctional() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Gallery gallery = new Gallery(activity, "{\"id\":124,\"num_pages\":3,"
            + "\"title\":{\"pretty\":\"Range test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
            + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        AlertDialog dialog = new RangeSelector(activity, gallery).create();
        dialog.show();
        for (int id : new int[]{R.id.layout1, R.id.layout2}) {
            View selector = dialog.findViewById(id);
            assertNotNull(selector);
            assertEquals(View.GONE, selector.findViewById(R.id.reader_page_preview).getVisibility());
            assertEquals(View.GONE, selector.findViewById(R.id.reader_settings).getVisibility());
            assertEquals(View.GONE, selector.findViewById(R.id.reader_auto_scroll).getVisibility());
            assertEquals(View.VISIBLE, selector.findViewById(R.id.prev).getVisibility());
            assertEquals(View.VISIBLE, selector.findViewById(R.id.next).getVisibility());
        }
        View first = dialog.findViewById(R.id.layout1);
        View last = dialog.findViewById(R.id.layout2);
        SeekBar firstSeek = first.findViewById(R.id.seekBar);
        SeekBar lastSeek = last.findViewById(R.id.seekBar);
        assertEquals(0, firstSeek.getProgress());
        assertEquals(2, lastSeek.getProgress());
        first.findViewById(R.id.next).performClick();
        last.findViewById(R.id.prev).performClick();
        assertEquals(1, firstSeek.getProgress());
        assertEquals(1, lastSeek.getProgress());
        assertEquals(activity.getString(R.string.page_format, 2, 3),
            ((TextView) first.findViewById(R.id.pages)).getText().toString());
        dialog.dismiss();
    }
}
