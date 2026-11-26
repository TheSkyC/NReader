package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.adapters.HistoryAdapter;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SearchHistoryRowTest {
    @Test public void queryWrapsWithoutOverlappingTheEditAction() {
        SearchActivity activity = Robolectric.buildActivity(SearchActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("ar", "SA")}) {
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            config.fontScale = 1.5f;
            Context context = activity.createConfigurationContext(config);
            context.setTheme(R.style.AppTheme_Material3);
            View row = LayoutInflater.from(context).inflate(R.layout.entry_history, null, false);
            TextView text = row.findViewById(R.id.text);
            ImageButton edit = row.findViewById(R.id.edit);
            text.setText("artist:example language:english");
            int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
            row.layout(0, 0, width, row.getMeasuredHeight());
            assertEquals(2, text.getLayout().getLineCount());
            assertEquals(0, text.getLayout().getEllipsisCount(1));
            assertTrue(text.getLayout().getHeight() <= text.getHeight());
            assertTrue(text.getRight() <= edit.getLeft() || text.getLeft() >= edit.getRight());
            assertTrue(edit.getHeight() >= Math.round(48 * context.getResources().getDisplayMetrics().density));
        }
    }

    @Test public void deleteModeUpdatesAccessibilityAndRemovesOnlyTheSelectedEntry() {
        SearchActivity activity = Robolectric.buildActivity(SearchActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Global.initFromShared(activity);
        activity.getSharedPreferences("History", 0).edit()
            .putStringSet("history", new HashSet<>(Arrays.asList("2|artist:example", "1|language:english"))).commit();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        HistoryAdapter adapter = new HistoryAdapter(activity);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 800, 1000);
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(0);
        ImageButton action = holder.itemView.findViewById(R.id.edit);
        assertEquals(activity.getString(R.string.edit), action.getContentDescription());
        assertTrue(action.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        recycler.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 800, 1000);
        holder = recycler.findViewHolderForAdapterPosition(0);
        action = holder.itemView.findViewById(R.id.edit);
        assertEquals(activity.getString(R.string.delete), action.getContentDescription());
        assertTrue(action.performClick());
        assertEquals(1, adapter.getItemCount());
        assertEquals(Collections.singleton("1|language:english"),
            activity.getSharedPreferences("History", 0).getStringSet("history", Collections.emptySet()));
    }
}
