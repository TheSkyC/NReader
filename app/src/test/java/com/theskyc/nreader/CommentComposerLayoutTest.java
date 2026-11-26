// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class CommentComposerLayoutTest {
    @Test
    public void standaloneComposerKeepsInputAndSendActionUsableAtLargeTextSizes() {
        CommentActivity activity = Robolectric.buildActivity(CommentActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        metrics.scaledDensity = metrics.density * configuration.fontScale;
        activity.getResources().updateConfiguration(configuration, metrics);

        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            View root = LayoutInflater.from(activity).inflate(R.layout.activity_comment, null, false);
            root.setLayoutDirection(direction);
            TextInputLayout input = root.findViewById(R.id.comment_input);
            input.setHelperText(activity.getString(R.string.comment_feature_unavailable));
            MaterialButton send = root.findViewById(R.id.sendButton);
            for (int[] size : new int[][]{{320, 640}, {640, 320}}) {
                int width = Math.round(size[0] * metrics.density);
                int height = Math.round(size[1] * metrics.density);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                assertNotNull(send.getIcon());
                assertTrue(send.getWidth() >= Math.round(48 * metrics.density));
                assertTrue(send.getHeight() >= Math.round(48 * metrics.density));
                assertTrue(input.getWidth() > send.getWidth());
                assertTrue(direction == View.LAYOUT_DIRECTION_LTR
                    ? input.getRight() <= send.getLeft() : input.getLeft() >= send.getRight());
                assertTrue(send.getBottom() <= ((View) send.getParent()).getHeight());
            }
        }
    }
}
