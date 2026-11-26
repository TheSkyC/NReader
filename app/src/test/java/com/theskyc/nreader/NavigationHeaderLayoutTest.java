package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NavigationHeaderLayoutTest {
    @Test public void guestLabelFitsNarrowDrawerAtLargeTextSize() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.setLocale(Locale.ENGLISH);
        configuration.fontScale = 1.5f;
        Context context = activity.createConfigurationContext(configuration);
        context.setTheme(R.style.AppTheme_Material3);
        ViewGroup header = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.nav_header_main, null, false);
        int width = Math.round(216 * context.getResources().getDisplayMetrics().density);
        header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        header.layout(0, 0, width, header.getMeasuredHeight());
        TextView name = header.findViewById(R.id.nav_account_name);
        for (int line = 0; line < name.getLayout().getLineCount(); line++)
            assertEquals(0, name.getLayout().getEllipsisCount(line));
        assertTrue(name.getLayout().getHeight() <= name.getHeight());
    }
}
