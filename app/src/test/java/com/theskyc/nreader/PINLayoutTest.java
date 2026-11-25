package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h320dp")
public class PINLayoutTest {
    @Test
    public void unlockRemainsAccessibleOnSmallScreensInBothDirections() {
        PINActivity activity = Robolectric.buildActivity(PINActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
        float density = activity.getResources().getDisplayMetrics().density;
        int size = Math.round(320 * density);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            ScrollView layout = (ScrollView) LayoutInflater.from(activity)
                .inflate(R.layout.activity_pin, null, false);
            layout.setLayoutDirection(direction);
            layout.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            layout.layout(0, 0, size, size);
            View button = layout.findViewById(R.id.unlockButton);
            assertTrue(button.getWidth() >= Math.round(48 * density));
            assertTrue(button.getHeight() >= Math.round(48 * density));
            assertTrue(button.getLeft() >= 0 && button.getRight() <= size);
            assertTrue(button.getBottom() <= layout.getChildAt(0).getHeight());
        }
    }
}
