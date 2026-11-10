// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.BookmarkActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.enums.ApiRequestType;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.SearchPlanStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class BookmarkAdapterTest {
    private DatabaseHelper helper;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("Entries.db");
        helper = new DatabaseHelper(RuntimeEnvironment.getApplication());
        helper.getWritableDatabase();
        for (int index = 1; index <= 3; index++) {
            ContentValues values = new ContentValues();
            values.put("url", "https://example.com/?query=" + index);
            values.put("page", 1);
            values.put("type", ApiRequestType.BYSEARCH.ordinal());
            values.put("tagId", 0);
            helper.getWritableDatabase().insertOrThrow("Bookmark", null, values);
        }
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void removingAdjacentRowsUsesTheirCurrentPositions() {
        BookmarkActivity activity = Robolectric.buildActivity(BookmarkActivity.class).setup().get();
        RecyclerView list = activity.findViewById(R.id.recycler);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
        RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder second = list.findViewHolderForAdapterPosition(1);
        assertNotNull(first);
        assertNotNull(second);
        first.itemView.findViewById(R.id.remove_button).performClick();
        second.itemView.findViewById(R.id.remove_button).performClick();
        assertEquals(1, Queries.BookmarkTable.getBookmarks().size());
        assertEquals("https://example.com/?query=3", Queries.BookmarkTable.getBookmarks().get(0).url);
    }

    @Test
    public void planOpensWithItsSavedQuerySortAndPage() throws Exception {
        ContentValues plan = new ContentValues();
        plan.put("name", "Example");
        plan.put("query", "artist:example -tag:other");
        plan.put("sort", "popular-week");
        plan.put("page", 3);
        plan.put("pinned", 0);
        SearchPlanStore.insert(plan);
        BookmarkActivity activity = Robolectric.buildActivity(BookmarkActivity.class).setup().get();
        CountDownLatch loaded = new CountDownLatch(1);
        SearchPlanStore.execute(loaded::countDown);
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
        RecyclerView list = activity.findViewById(R.id.recycler);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        holder.itemView.findViewById(R.id.master_layout).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        InspectorV3 inspector = intent.getParcelableExtra(activity.getPackageName() + ".INSPECTOR");
        assertNotNull(inspector);
        Uri url = Uri.parse(inspector.getUrl());
        assertEquals("artist:example -tag:other", url.getQueryParameter("query"));
        assertEquals("popular-week", url.getQueryParameter("sort"));
        assertEquals("3", url.getQueryParameter("page"));
    }

    @Test
    public void planActionsEditCopyPinAndShare() throws Exception {
        ContentValues plan = new ContentValues();
        plan.put("name", "Original");
        plan.put("query", "tag:one");
        plan.put("sort", "popular-week");
        plan.put("page", 3);
        plan.put("pinned", 0);
        SearchPlanStore.insert(plan);
        BookmarkActivity activity = Robolectric.buildActivity(BookmarkActivity.class).setup().get();
        awaitPlans();
        RecyclerView list = activity.findViewById(R.id.recycler);
        layout(list);
        openPlanActions(list);
        chooseAction(0);
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        ((EditText) editor.findViewById(R.id.plan_name)).setText("Changed");
        ((EditText) editor.findViewById(R.id.plan_query)).setText("artist:two -tag:x");
        ((EditText) editor.findViewById(R.id.plan_page)).setText("7");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        awaitPlans();
        assertEquals("Changed", SearchPlanStore.list().get(0).getAsString("name"));
        assertEquals("artist:two -tag:x", SearchPlanStore.list().get(0).getAsString("query"));
        assertEquals(7, SearchPlanStore.list().get(0).getAsInteger("page").intValue());
        layout(list);
        openPlanActions(list);
        chooseAction(1);
        awaitPlans();
        assertEquals(2, SearchPlanStore.list().size());
        layout(list);
        openPlanActions(list);
        chooseAction(2);
        awaitPlans();
        assertEquals(1, SearchPlanStore.list().get(0).getAsInteger("pinned").intValue());
        layout(list);
        openPlanActions(list);
        chooseAction(3);
        Intent chooser = shadowOf(activity).getNextStartedActivity();
        Intent shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        Uri link = Uri.parse(shared.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals("artist:two -tag:x", link.getQueryParameter("q"));
        assertEquals("popular-week", link.getQueryParameter("sort"));
        assertEquals("7", link.getQueryParameter("page"));
    }

    private void awaitPlans() throws Exception {
        for (int index = 0; index < 2; index++) {
            CountDownLatch pending = new CountDownLatch(1);
            SearchPlanStore.execute(pending::countDown);
            assertTrue(pending.await(10, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private void layout(RecyclerView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
    }

    private void openPlanActions(RecyclerView list) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        holder.itemView.findViewById(R.id.plan_actions).performClick();
    }

    private void chooseAction(int position) {
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getListView().performItemClick(dialog.getListView().getChildAt(position), position, position);
    }
}
