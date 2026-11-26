// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

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
public class AccountToolbarLayoutTest {
    @Test
    public void localizedTitlesFitAtLargeTextSizes() {
        AccountsActivity activity = Robolectric.buildActivity(AccountsActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("uk", "UA"), new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = activity.createConfigurationContext(configuration);
            context.setTheme(R.style.AppTheme_Material3);
            int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
            int height = Math.round(640 * context.getResources().getDisplayMetrics().density);
            for (int[] item : new int[][]{
                {R.layout.activity_accounts, R.string.accounts_title},
                {R.layout.activity_account_login, R.string.account_title},
                {R.layout.activity_api_key, R.string.title_activity_api_key}
            }) {
                View root = LayoutInflater.from(context).inflate(item[0], null, false);
                Toolbar toolbar = root.findViewById(R.id.toolbar);
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
                toolbar.setTitle(item[1]);
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
                for (int line = 0; line < title.getLayout().getLineCount(); line++) {
                    assertEquals(locale + ": " + title.getText(), 0, title.getLayout().getEllipsisCount(line));
                }
                assertTrue(locale + ": " + title.getText() + " title=" + title.getTop() + ".."
                    + title.getBottom() + " toolbar=" + toolbar.getHeight(),
                    title.getTop() >= 0 && title.getBottom() <= toolbar.getHeight());
                assertTrue(title.getLayout().getHeight() <= title.getHeight());
                assertTrue(locale + ": title overlaps navigation", title.getRight() <= navigation.getLeft()
                    || title.getLeft() >= navigation.getRight());
                assertTrue(((ViewGroup) root).getChildAt(1).getTop() >= toolbar.getBottom());
            }
        }
    }
}
