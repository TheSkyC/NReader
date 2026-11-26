package com.theskyc.nreader.components.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;
import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w540dp-h320dp-land")
public class TagSearchResultsLayoutTest {
    @Test public void landscapeTagRowKeepsAccessibleTouchTargetInCompactViewport() {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.fontScale = 1.5f;
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config),
            R.style.AppTheme_Material3);
        View row = LayoutInflater.from(context).inflate(R.layout.entry_tag_compact_layout, null, false);
        View target = row.findViewById(R.id.master_layout);
        TextView title = row.findViewById(R.id.title);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(240 * density);
        int height = Math.round(52 * density);

        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        row.layout(0, 0, width, row.getMeasuredHeight());

        assertTrue(target.getMeasuredHeight() >= Math.round(48 * density));
        assertTrue(row.getMeasuredHeight() <= height);
        assertEquals(1, title.getMaxLines());
        assertTrue(target.isClickable());
    }

    @Test public void shortLandscapeKeepsCategoriesAndResultsVisibleAndRestoresTheNormalLayout() {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.fontScale = 1.5f;
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config),
            R.style.AppTheme_Material3);
        float density = context.getResources().getDisplayMetrics().density;
        TagSearchResultsLayout layout = new TagSearchResultsLayout(context, null);
        layout.setOrientation(LinearLayout.VERTICAL);
        TabLayout tabs = new TabLayout(context);
        tabs.setId(R.id.tabs);
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.addTab(tabs.newTab().setText("Tags"));
        layout.addView(tabs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView results = new TextView(context);
        results.setId(R.id.container);
        results.setText("No matching tags.");
        layout.addView(results, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.round(540 * density);
        int shortHeight = Math.round(52 * density);
        measure(layout, width, shortHeight);
        assertEquals(LinearLayout.HORIZONTAL, layout.getOrientation());
        assertTrue(tabs.getWidth() > 0);
        assertTrue(tabs.getHeight() >= Math.round(48 * density));
        assertTrue(results.getWidth() > tabs.getWidth());
        assertEquals(shortHeight, results.getHeight());

        measure(layout, width, Math.round(200 * density));
        assertEquals(LinearLayout.VERTICAL, layout.getOrientation());
        assertEquals(width, tabs.getWidth());
        assertTrue(results.getHeight() > 0);
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
