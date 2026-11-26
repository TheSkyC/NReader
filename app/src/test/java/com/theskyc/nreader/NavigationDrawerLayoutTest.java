package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.navigation.NavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class NavigationDrawerLayoutTest {
    @Test public void menuLabelsWrapAtLargeTextSizeInBothDirections() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        ContextThemeWrapper context = new ContextThemeWrapper(
            activity.createConfigurationContext(configuration), R.style.AppTheme_Material3);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(264 * density);
        int height = Math.round(640 * density);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false);
            NavigationView navigation = root.findViewById(R.id.nav_view);
            navigation.setLayoutDirection(direction);
            navigation.getMenu().findItem(R.id.downloaded)
                .setTitle("Downloaded galleries\nLocal items");
            navigation.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            navigation.layout(0, 0, width, height);
            View item = navigation.findViewById(R.id.downloaded);
            assertNotNull(item);
            TextView title = item.findViewById(com.google.android.material.R.id.design_menu_item_text);
            assertNotNull(title);
            assertTrue(title.getLayout().getLineCount() > 1);
            assertEquals(0, title.getLayout().getEllipsisCount(title.getLayout().getLineCount() - 1));
            assertTrue(item.getHeight() >= Math.round(48 * density));
            assertTrue(title.getBottom() <= item.getHeight());
        }
    }
}
