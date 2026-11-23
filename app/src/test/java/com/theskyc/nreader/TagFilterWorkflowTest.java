package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.components.widgets.TagTypePage;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class TagFilterWorkflowTest {
    @Test public void aliasSearchEditAppliedRuleClearAndRecreate() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.resetFilterPreferences(app);
        Tag wanted = new Tag("canonical fixture", 60, 500_001, TagType.TAG, TagStatus.DEFAULT);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            Queries.TagTable.insert(wanted);
            Queries.TagTable.insert(new Tag("unrelated", 50, 500_002, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
                + "\"entries\":[{\"id\":500001,\"name\":\"Localized fixture\",\"aliases\":[\"lookup phrase\"]}]}";
            assertTrue(TagTranslationCatalog.apply(db,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            Intent launch = new Intent(app, TagFilterActivity.class).setData(Uri.parse("https://nhentai.net/tags/"));
            try (ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class, launch).setup()) {
                TagFilterActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                Toolbar toolbar = activity.findViewById(R.id.toolbar);
                assertEquals("Minimum gallery count per tag (25)",
                    toolbar.getMenu().findItem(R.id.set_min_count).getTitle().toString());
                TagV2.updateMinCount(app, 80);
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.reset_view_filters));
                assertEquals("Minimum gallery count per tag (25)",
                    toolbar.getMenu().findItem(R.id.set_min_count).getTitle().toString());
                await(activity, () -> count(activity, 1) == 2);
                SearchView search = (SearchView) toolbar.getMenu().findItem(R.id.search).getActionView();
                search.setQuery("lookup phrase", false);
                await(activity, () -> count(activity, 1) == 1 && firstRow(activity, 1) != null);
                View found = firstRow(activity, 1);
                assertTrue(((TextView) found.findViewById(R.id.title)).getText().toString().contains("Localized fixture"));
                assertTrue(found.findViewById(R.id.master_layout).performClick());
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.recent_tags));
                AlertDialog recent = (AlertDialog) ShadowDialog.getLatestDialog();
                assertNotNull(recent);
                assertEquals("canonical fixture (Tags)", recent.getListView().getItemAtPosition(0).toString());
                assertTrue(recent.getListView().performItemClick(null, 0, 0));
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.recent_tags));
                AlertDialog recentAgain = (AlertDialog) ShadowDialog.getLatestDialog();
                assertNotNull(recentAgain);
                assertTrue(recentAgain.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
                pager.setCurrentItem(0, false);
                await(activity, () -> count(activity, 0) == 1 && firstRow(activity, 0) != null);
                assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getStatus(wanted));

                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.reset_tags));
                AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
                assertNotNull(confirm);
                assertTrue(confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                await(activity, () -> count(activity, 0) == 0);
                assertEquals(TagStatus.DEFAULT, Queries.TagTable.getStatus(wanted));

                controller.recreate();
                TagFilterActivity recreated = controller.get();
                ViewPager2 recreatedPager = recreated.findViewById(R.id.container);
                recreatedPager.setCurrentItem(0, false);
                await(recreated, () -> count(recreated, 0) == 0);
                assertEquals(TagStatus.DEFAULT, Queries.TagTable.getStatus(wanted));
            }
        } finally {
            Queries.setDb(null);
        }
    }

    private static RecyclerView list(TagFilterActivity activity, int page) {
        TagTypePage fragment = (TagTypePage) activity.getSupportFragmentManager().findFragmentByTag("f" + page);
        return fragment == null ? null : fragment.getRecyclerView();
    }

    private static int count(TagFilterActivity activity, int page) {
        RecyclerView list = list(activity, page);
        return list == null || list.getAdapter() == null ? -1 : list.getAdapter().getItemCount();
    }

    private static View firstRow(TagFilterActivity activity, int page) {
        RecyclerView list = list(activity, page);
        if (list == null) return null;
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
        return holder == null ? null : holder.itemView;
    }

    private static void await(TagFilterActivity activity, BooleanSupplier condition) {
        View decor = activity.getWindow().getDecorView();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            decor.measure(width, height);
            decor.layout(0, 0, 1080, 1920);
            if (condition.getAsBoolean()) return;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        assertTrue("Timed out waiting for the tag editor", condition.getAsBoolean());
    }
}
