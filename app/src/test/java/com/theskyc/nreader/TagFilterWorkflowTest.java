package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagViewStore;
import com.theskyc.nreader.components.widgets.TagTypePage;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.TagV2;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.textfield.TextInputLayout;

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
    @Test public void materialSearchRestoresExpandedQueryAndCollapsedLabel() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.resetFilterPreferences(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            Database.setDatabase(helper.getWritableDatabase());
            Intent launch = new Intent(app, TagFilterActivity.class).setData(Uri.parse("https://nhentai.net/tags/"));
            try (ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class, launch).setup()) {
                TagFilterActivity activity = controller.get();
                SearchBar searchBar = activity.findViewById(R.id.toolbar);
                SearchView searchView = activity.findViewById(R.id.search_view);
                assertFalse(searchView.isShowing());

                searchBar.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(searchView.isShowing());
                searchView.setText("fixture");
                assertEquals("fixture", searchBar.getText().toString());

                controller.recreate();
                TagFilterActivity restored = controller.get();
                SearchBar restoredBar = restored.findViewById(R.id.toolbar);
                SearchView restoredView = restored.findViewById(R.id.search_view);
                assertTrue(restoredView.isShowing());
                assertEquals("fixture", restored.getSearchQuery());
                assertEquals("fixture", restoredView.getEditText().getText().toString());

                restoredView.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(restoredView.isShowing());
                assertEquals("fixture", restoredBar.getText().toString());
            }
        } finally {
            Queries.setDb(null);
        }
    }

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
                SearchView search = activity.findViewById(R.id.search_view);
                search.setText("lookup phrase");
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

    @Test public void bulkEditUsesSearchResultsAndShowsAffectedCount() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.resetFilterPreferences(app);
        Tag first = new Tag("bulk fixture one", 60, 500_011, TagType.TAG, TagStatus.DEFAULT);
        Tag second = new Tag("bulk fixture two", 60, 500_012, TagType.TAG, TagStatus.DEFAULT);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            Queries.TagTable.insert(first);
            Queries.TagTable.insert(second);
            Intent launch = new Intent(app, TagFilterActivity.class).setData(Uri.parse("https://nhentai.net/tags/"));
            try (ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class, launch).setup()) {
                TagFilterActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                pager.setCurrentItem(1, false);
                await(activity, () -> count(activity, 1) == 2);
                Toolbar toolbar = activity.findViewById(R.id.toolbar);
                SearchView search = activity.findViewById(R.id.search_view);
                search.setText("bulk fixture");
                await(activity, () -> toolbar.getMenu().findItem(R.id.bulk_edit_rules).isVisible());

                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.bulk_edit_rules));
                await(activity, () -> ShadowDialog.getLatestDialog() instanceof AlertDialog);
                AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
                TextView message = confirm.findViewById(android.R.id.message);
                assertNotNull(message);
                assertTrue(message.getText().toString().contains("2"));
                assertTrue(confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                await(activity, () -> Queries.TagTable.getStatus(first) == TagStatus.ACCEPTED
                    && Queries.TagTable.getStatus(second) == TagStatus.ACCEPTED);
                await(activity, () -> toolbar.getMenu().findItem(R.id.undo_bulk_edit).isVisible());
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.undo_bulk_edit));
                await(activity, () -> Queries.TagTable.getStatus(first) == TagStatus.DEFAULT
                    && Queries.TagTable.getStatus(second) == TagStatus.DEFAULT
                    && !toolbar.getMenu().findItem(R.id.undo_bulk_edit).isVisible());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void savesAndRestoresTagViewFilters() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.resetFilterPreferences(app);
        Tag fixture = new Tag("saved view fixture", 60, 500_021, TagType.TAG, TagStatus.DEFAULT);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TagViewStore.TABLE_NAME, null, null);
            Queries.TagTable.insert(fixture);
            Intent launch = new Intent(app, TagFilterActivity.class).setData(Uri.parse("https://nhentai.net/tags/"));
            try (ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class, launch).setup()) {
                TagFilterActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                pager.setCurrentItem(1, false);
                await(activity, () -> count(activity, 1) == 1);
                Toolbar toolbar = activity.findViewById(R.id.toolbar);
                SearchView search = activity.findViewById(R.id.search_view);
                search.setText("saved view");
                TagV2.updateMinCount(app, 77);
                TagV2.setSortByName(app, true);
                TagV2.updateStatusFilter(app, TagStatus.ACCEPTED);
                TagV2.setTranslatedOnly(app, true);
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.save_tag_view));
                await(activity, () -> ShadowDialog.getLatestDialog() instanceof AlertDialog);
                AlertDialog save = (AlertDialog) ShadowDialog.getLatestDialog();
                EditText name = save.findViewById(android.R.id.edit);
                TextInputLayout nameInput = save.findViewById(R.id.name_input);
                assertNotNull(name);
                assertNotNull(nameInput);
                name.setText("");
                assertTrue(save.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                assertTrue("Save dialog should remain open after rejecting an empty name", save.isShowing());
                assertEquals(activity.getString(R.string.tag_view_invalid_name), nameInput.getError().toString());
                name.setText("Review view");
                assertEquals(null, nameInput.getError());
                assertTrue(save.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                await(activity, () -> TagViewStore.list().size() == 1);

                search.setText("changed");
                TagV2.updateMinCount(app, 25);
                TagV2.setSortByName(app, false);
                TagV2.updateStatusFilter(app, null);
                TagV2.setTranslatedOnly(app, false);
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.saved_tag_views));
                await(activity, () -> ShadowDialog.getLatestDialog() instanceof AlertDialog);
                AlertDialog saved = (AlertDialog) ShadowDialog.getLatestDialog();
                assertEquals("Review view", saved.getListView().getItemAtPosition(0).toString());
                assertTrue(saved.getListView().performItemClick(null, 0, 0));
                await(activity, () -> "saved view".equals(activity.getSearchQuery())
                    && TagV2.getMinCount() == 77 && TagV2.isSortedByName()
                    && TagV2.getStatusFilter() == TagStatus.ACCEPTED && TagV2.isTranslatedOnly());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void managesSavedTagViews() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.resetFilterPreferences(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TagViewStore.TABLE_NAME, null, null);
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("name", "Original");
            values.put("query", "fixture");
            values.put("tag_type", 3);
            values.put("min_count", 25);
            values.put("sort_by_name", 0);
            values.put("status_filter", -1);
            values.put("translated_only", 0);
            values.put("pinned", 0);
            TagViewStore.insert(values);
            Intent launch = new Intent(app, TagFilterActivity.class).setData(Uri.parse("https://nhentai.net/tags/"));
            try (ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class, launch).setup()) {
                TagFilterActivity activity = controller.get();
                Toolbar toolbar = activity.findViewById(R.id.toolbar);
                activity.onOptionsItemSelected(toolbar.getMenu().findItem(R.id.saved_tag_views));
                await(activity, () -> ShadowDialog.getLatestDialog() instanceof AlertDialog
                    && ((AlertDialog) ShadowDialog.getLatestDialog()).getListView() != null);
                AlertDialog list = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(list.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
                await(activity, () -> ShadowDialog.getLatestDialog() != list
                    && ((AlertDialog) ShadowDialog.getLatestDialog()).getListView() != null);
                AlertDialog manage = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(manage.getListView().performItemClick(null, 0, 0));
                AlertDialog actions = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(actions.getListView().performItemClick(null, 1, 1));
                await(activity, () -> TagViewStore.list().get(0).getAsInteger("pinned") == 1);
                AlertDialog pinActions = actions;
                await(activity, () -> ShadowDialog.getLatestDialog() != pinActions
                    && ((AlertDialog) ShadowDialog.getLatestDialog()).getListView() != null);
                manage = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(manage.getListView().getItemAtPosition(0).toString().contains("Pinned"));
                assertTrue(manage.getListView().performItemClick(null, 0, 0));
                actions = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(actions.getListView().performItemClick(null, 0, 0));
                AlertDialog rename = (AlertDialog) ShadowDialog.getLatestDialog();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                EditText renameName = rename.findViewById(android.R.id.edit);
                TextInputLayout renameInput = rename.findViewById(R.id.name_input);
                renameName.setText("");
                assertTrue(rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                assertTrue("Rename dialog should remain open after rejecting an empty name", rename.isShowing());
                assertEquals(activity.getString(R.string.tag_view_invalid_name), renameInput.getError().toString());
                renameName.setText("Renamed");
                assertEquals(null, renameInput.getError());
                assertTrue(rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                assertTrue("Rename dialog should close after accepting a valid name", !rename.isShowing());
                await(activity, () -> "Renamed".equals(TagViewStore.list().get(0).getAsString("name")));
                await(activity, () -> ShadowDialog.getLatestDialog() != rename
                    && ((AlertDialog) ShadowDialog.getLatestDialog()).getListView() != null);
                manage = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(manage.getListView().performItemClick(null, 0, 0));
                actions = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(actions.getListView().performItemClick(null, 2, 2));
                AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                await(activity, () -> TagViewStore.list().isEmpty());
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
