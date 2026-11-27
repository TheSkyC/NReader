package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

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
public class SearchSuggestionLayoutTest {
    @Test
    public void longSuggestionsWrapWithoutClippingAtLargeText() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            for (int theme : new int[]{R.style.AppTheme_Material3, R.style.AppTheme_Material3_Black}) {
                Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), theme);
                TextView row = (TextView) LayoutInflater.from(context).inflate(R.layout.search_tag_suggestion, null);
                row.setLayoutDirection(configuration.getLayoutDirection());
                row.setText("A long translated tag label with its original name");
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(200 * density);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight());
                assertTrue(row.getHeight() >= Math.round(48 * density));
                assertTrue(row.getLineCount() > 1);
                assertTrue(row.getHeight() >= row.getLayout().getHeight()
                    + row.getCompoundPaddingTop() + row.getCompoundPaddingBottom());
                assertEquals(0, row.getLayout().getEllipsisCount(row.getLineCount() - 1));
            }
        }
    }
}
