package com.theskyc.nreader;

import android.app.Application;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
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
public class CommentLayoutTest {
    @Test
    public void bodyUsesAvailableWidthBelowTheHeaderInBothLayoutDirections() {
        GalleryActivity activity = Robolectric.buildActivity(GalleryActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        metrics.scaledDensity = metrics.density * configuration.fontScale;
        activity.getResources().updateConfiguration(configuration, metrics);
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            for (boolean canDelete : new boolean[]{false, true}) {
                for (String name : new String[]{"A", "A very long username that wraps across several lines"}) {
                    View row = LayoutInflater.from(activity).inflate(R.layout.comment_layout, null, false);
                    row.setLayoutDirection(direction);
                    TextView username = row.findViewById(R.id.username);
                    TextView date = row.findViewById(R.id.date);
                    TextView body = row.findViewById(R.id.body);
                    View delete = row.findViewById(R.id.close);
                    username.setText(name);
                    date.setText("November 25, 2026");
                    body.setText("A long comment that should remain readable and not overlap its delete action.");
                    delete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
                    int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    row.layout(0, 0, width, row.getMeasuredHeight());
                    assertTrue(username.getWidth() > 0 && body.getWidth() > 0);
                    assertTrue(date.getTop() >= username.getBottom());
                    assertTrue(body.getTop() >= date.getBottom());
                    if (canDelete) {
                        assertTrue(direction == View.LAYOUT_DIRECTION_LTR
                            ? username.getRight() <= delete.getLeft() : username.getLeft() >= delete.getRight());
                        assertTrue(body.getTop() >= delete.getBottom());
                    }
                    if (direction == View.LAYOUT_DIRECTION_LTR) {
                        assertEquals(row.getWidth() - row.getPaddingRight(), body.getRight());
                    } else {
                        assertEquals(row.getPaddingLeft(), body.getLeft());
                    }
                    assertTrue(body.getBottom() <= row.getBottom());
                }
            }
        }
    }
}
