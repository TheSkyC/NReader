// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.content.Context;
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

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp")
public class ApiKeyLayoutTest {
    @Test
    public void actionsHaveRoomForLocalizedLabelsAtLargeTextSizes() {
        ApiKeyActivity activity = Robolectric.buildActivity(ApiKeyActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN, new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = activity.createConfigurationContext(configuration);
            context.setTheme(R.style.AppTheme_Material3);
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            View root = LayoutInflater.from(context).inflate(R.layout.activity_api_key, null, false);
            TextView open = root.findViewById(R.id.open_api_key_page);
            TextView clear = root.findViewById(R.id.clear_api_key);
            for (int[] size : new int[][]{{320, 640}, {640, 320}}) {
                int width = Math.round(size[0] * metrics.density);
                int height = Math.round(size[1] * metrics.density);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                assertTrue(open.getWidth() >= Math.round(200 * metrics.density));
                assertEquals(open.getWidth(), clear.getWidth());
                assertTrue(clear.getTop() >= open.getBottom());
                for (TextView button : new TextView[]{open, clear}) {
                    assertTrue(button.getHeight() >= Math.round(48 * metrics.density));
                    for (int line = 0; line < button.getLayout().getLineCount(); line++) {
                        assertEquals(0, button.getLayout().getEllipsisCount(line));
                    }
                }
            }
        }
    }
}
