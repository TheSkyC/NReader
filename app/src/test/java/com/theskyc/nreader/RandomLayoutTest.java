// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w640dp-h320dp-land")
public class RandomLayoutTest {
    @Test
    public void landscapeTitleDoesNotOverlapActionsAtLargeTextSize() {
        RandomActivity activity = Robolectric.buildActivity(RandomActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        Context context = activity.createConfigurationContext(configuration);
        context.setTheme(R.style.AppTheme_Material3);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_random, null, false);
        TextView title = root.findViewById(R.id.title);
        title.setText("A long gallery title that occupies several lines of the available space");
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(640 * density);
        int height = Math.round(320 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View language = root.findViewById(R.id.language);
        View shuffle = root.findViewById(R.id.shuffle);
        assertTrue("Title overlaps the language indicator", title.getTop() >= language.getBottom());
        assertTrue("Title overlaps the shuffle action", title.getBottom() <= shuffle.getTop());
        assertTrue("Title text is clipped", title.getLayout().getHeight() <= title.getHeight());
    }
}
