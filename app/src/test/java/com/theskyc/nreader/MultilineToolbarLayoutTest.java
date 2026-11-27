package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.theskyc.nreader.components.widgets.MultilineMaterialToolbar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MultilineToolbarLayoutTest {
    @Test
    public void longTitleWrapsAtLargeTextSizesWithoutOverlappingContent() {
        AccountsActivity activity = Robolectric.buildActivity(AccountsActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        Context context = activity.createConfigurationContext(configuration);
        context.setTheme(R.style.AppTheme_Material3);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        MultilineMaterialToolbar toolbar = new MultilineMaterialToolbar(context, null);
        toolbar.setTitleTextAppearance(context, R.style.TextAppearance_Material3_TitleLarge);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        int verticalPadding = Math.round(4 * context.getResources().getDisplayMetrics().density);
        toolbar.setPadding(0, verticalPadding, 0, verticalPadding);
        toolbar.setTitleMarginTop(0);
        toolbar.setTitleMarginBottom(0);
        toolbar.setTitle(R.string.settings_galleries);
        root.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View content = new View(context);
        root.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
        int height = Math.round(640 * context.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);

        TextView title = null;
        View navigation = null;
        for (int index = 0; index < toolbar.getChildCount(); index++) {
            View child = toolbar.getChildAt(index);
            if (child instanceof TextView && toolbar.getTitle().equals(((TextView) child).getText())) {
                title = (TextView) child;
            }
            if (child instanceof ImageButton) navigation = child;
        }

        assertNotNull(title);
        assertNotNull(navigation);
        assertTrue(title.getLayout().getLineCount() > 1);
        for (int line = 0; line < title.getLayout().getLineCount(); line++) {
            assertEquals(0, title.getLayout().getEllipsisCount(line));
        }
        assertTrue("title=" + title.getTop() + ".." + title.getBottom() + " toolbar=" + toolbar.getHeight()
            + " titleHeight=" + title.getHeight() + " layoutHeight=" + title.getLayout().getHeight(),
            title.getTop() >= 0 && title.getBottom() <= toolbar.getHeight());
        assertTrue(title.getRight() <= navigation.getLeft() || title.getLeft() >= navigation.getRight());
        assertTrue(content.getTop() >= toolbar.getBottom());
    }
}
