package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.adapters.ReaderBookmarkAdapter;
import com.theskyc.nreader.components.classes.ReaderBookmark;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
public class ReaderBookmarkItemTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void rowKeepsContentAndActionsUsableAtLargeTextAndAfterRebinding() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                R.style.AppTheme_Material3);
            RecyclerView parent = new RecyclerView(context);
            parent.setLayoutManager(new LinearLayoutManager(context));
            ReaderBookmark first = new ReaderBookmark(1, 10, 123, "A long bookmark title for this page",
                "A note that wraps across multiple lines when the screen is narrow.", 0, 0);
            ReaderBookmark second = new ReaderBookmark(2, 10, 4, "Another title", "", 0, 0);
            ReaderBookmark[] opened = {null};
            ReaderBookmark[] deleted = {null};
            RecyclerView.Adapter adapter = new ReaderBookmarkAdapter(Arrays.asList(first, second),
                bookmark -> opened[0] = bookmark, bookmark -> deleted[0] = bookmark);
            RecyclerView.ViewHolder holder = adapter.createViewHolder(parent, 0);
            adapter.bindViewHolder(holder, 0);
            View row = holder.itemView;
            row.setLayoutDirection(configuration.getLayoutDirection());
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(248 * density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());

            View open = row.findViewById(R.id.reader_bookmark_open);
            View remove = row.findViewById(R.id.reader_bookmark_remove);
            TextView title = row.findViewById(R.id.reader_bookmark_item_title);
            TextView page = row.findViewById(R.id.reader_bookmark_item_page);
            TextView note = row.findViewById(R.id.reader_bookmark_item_note);
            assertTrue(open.isClickable());
            assertTrue(remove.getWidth() >= Math.round(48 * density));
            assertTrue(remove.getHeight() >= Math.round(48 * density));
            assertTrue(open.getRight() <= remove.getLeft() || remove.getRight() <= open.getLeft());
            for (TextView text : new TextView[]{title, page, note}) {
                assertTrue(text.getHeight() >= text.getLayout().getHeight());
                assertEquals(0, text.getLayout().getEllipsisCount(text.getLineCount() - 1));
            }
            open.performClick();
            remove.performClick();
            assertSame(first, opened[0]);
            assertSame(first, deleted[0]);
            assertEquals(context.getString(R.string.reader_bookmark_delete_page, first.page),
                remove.getContentDescription());

            adapter.bindViewHolder(holder, 1);
            assertEquals(View.GONE, note.getVisibility());
            assertEquals(second.title, title.getText().toString());
            remove.performClick();
            assertSame(second, deleted[0]);
        }
    }
}
