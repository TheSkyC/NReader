// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class FavoriteSearchTest {
    @Test
    public void collapsedResultsRemainWithinTheViewportAfterSearch() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).setup()) {
                FavoriteActivity activity = controller.get();
                awaitCount(activity, 0);
                View root = activity.findViewById(android.R.id.content);
                SearchView search = activity.findViewById(R.id.favorite_search_view);
                search.show();
                search.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View results = activity.findViewById(R.id.master_layout);
                for (int[] size : new int[][]{{864, 1536}, {1536, 864}}) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, size[0], size[1]);
                    int bottom = results.getHeight();
                    for (View view = results; view != root; view = (View) view.getParent()) bottom += view.getTop();
                    assertTrue("favorite results extend below the viewport: " + bottom, bottom <= root.getHeight());
                }
            }
        }
    }

    @Test
    public void emptySearchLoadingAndFailureStatesFollowTheLatestResult() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).setup()) {
                FavoriteActivity activity = controller.get();
                awaitCount(activity, 0);
                View state = activity.findViewById(R.id.content_state);
                TextView message = activity.findViewById(R.id.content_state_message);
                View retry = activity.findViewById(R.id.content_state_action);
                assertEquals(View.VISIBLE, state.getVisibility());
                assertEquals(activity.getString(R.string.favorites_empty), message.getText().toString());
                assertEquals(View.GONE, retry.getVisibility());

                SearchView search = activity.findViewById(R.id.favorite_search_view);
                search.getEditText().setText("no match");
                assertEquals(View.GONE, state.getVisibility());
                awaitCount(activity, 0);
                assertEquals(activity.getString(R.string.no_entry_found), message.getText().toString());
                assertEquals(View.VISIBLE, state.getVisibility());

                activity.onFavoritesLoading();
                assertEquals(View.GONE, state.getVisibility());
                activity.onFavoritesLoadFailed();
                assertEquals(View.VISIBLE, state.getVisibility());
                assertEquals(activity.getString(R.string.favorites_load_failed), message.getText().toString());
                assertEquals(View.VISIBLE, retry.getVisibility());
                retry.performClick();
                awaitCount(activity, 0);
                assertEquals(activity.getString(R.string.no_entry_found), message.getText().toString());
                assertEquals(View.GONE, retry.getVisibility());

                Queries.FavoriteTable.addFavorite(gallery(123, "Synthetic favorite"));
                search.getEditText().setText("no match again");
                search.getEditText().setText("Synthetic");
                awaitCount(activity, 1);
                assertEquals(View.GONE, state.getVisibility());
                activity.onFavoritesLoadFailed();
                assertEquals(View.GONE, state.getVisibility());
                assertEquals(1, ((androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.recycler))
                    .getAdapter().getItemCount());
            }
            Thread.sleep(100);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void collapseRetainsQueryAndRecreationRestoresItWithoutOpeningSearch() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            Queries.FavoriteTable.addFavorite(gallery(123, "Synthetic favorite"));
            Bundle state = new Bundle();
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).setup()) {
                FavoriteActivity activity = controller.get();
                SearchBar searchBar = activity.findViewById(R.id.favorite_search_bar);
                Menu menu = searchBar.getMenu();
                activity.onCreateOptionsMenu(menu);
                MenuItem item = menu.findItem(R.id.search);
                assertFalse(item.isVisible());
                MenuItem random = menu.findItem(R.id.random_favorite);
                assertTrue(random.isVisible());
                assertFalse(((androidx.appcompat.view.menu.MenuItemImpl) random).requestsActionButton());
                assertFalse(((androidx.appcompat.view.menu.MenuItemImpl) random).requiresActionButton());
                MenuItem browser = menu.findItem(R.id.open_browser);
                assertTrue(browser.isVisible());
                assertFalse(((androidx.appcompat.view.menu.MenuItemImpl) browser).requestsActionButton());
                assertFalse(((androidx.appcompat.view.menu.MenuItemImpl) browser).requiresActionButton());
                SearchView search = activity.findViewById(R.id.favorite_search_view);
                search.show();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(isInside(activity.findViewById(R.id.recycler), search));
                search.getEditText().setText("Synthetic");
                awaitCount(activity, 1);
                search.getEditText().setText("No matching fixture");
                awaitCount(activity, 0);
                search.getEditText().setText("Synthetic");
                awaitCount(activity, 1);
                activity.getOnBackPressedDispatcher().onBackPressed();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(activity.isFinishing());
                assertFalse(isInside(activity.findViewById(R.id.recycler), search));
                activity.onSaveInstanceState(state);
                assertEquals("Synthetic", state.getString("favorite_query"));
                assertFalse(state.getBoolean("favorite_search_expanded"));
            }
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).create(state).start().resume().visible()) {
                FavoriteActivity activity = controller.get();
                SearchBar searchBar = activity.findViewById(R.id.favorite_search_bar);
                Menu menu = searchBar.getMenu();
                activity.onCreateOptionsMenu(menu);
                SearchView search = activity.findViewById(R.id.favorite_search_view);
                assertFalse(search.isShowing());
                search.show();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Synthetic", search.getEditText().getText().toString());
                search.getEditText().setText("");
                Bundle cleared = new Bundle();
                activity.onSaveInstanceState(cleared);
                assertEquals("", cleared.getString("favorite_query"));
            }
            Thread.sleep(100);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void savedPageSortAndExpandedQueryRestoreTogether() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            for (int index = 0; index < 25; index++)
                Queries.FavoriteTable.addFavorite(gallery(1000 + index, String.format(java.util.Locale.US, "Fixture %02d", index)));
            Bundle state = new Bundle();
            state.putString("favorite_query", "Fixture");
            state.putInt("favorite_page", 2);
            state.putBoolean("favorite_sort_title", true);
            state.putBoolean("favorite_search_expanded", true);
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).create(state).start().resume().visible()) {
                FavoriteActivity activity = controller.get();
                SearchBar searchBar = activity.findViewById(R.id.favorite_search_bar);
                Menu menu = searchBar.getMenu();
                activity.onCreateOptionsMenu(menu);
                awaitCount(activity, 1);
                assertEquals(2, activity.getActualPage());
                androidx.recyclerview.widget.RecyclerView recycler = activity.findViewById(R.id.recycler);
                assertEquals(1024, recycler.getAdapter().getItemId(0));
                assertTrue(((SearchView) activity.findViewById(R.id.favorite_search_view)).isShowing());
            }
            Thread.sleep(100);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private Gallery gallery(int id, String title) throws Exception {
        return new Gallery(null, "{\"id\":" + id + ",\"num_pages\":1,\"title\":{\"pretty\":\"" + title
            + "\"},\"cover\":{\"path\":\"cover.png\"},\"thumbnail\":{\"path\":\"thumb.png\"},\"pages\":[{\"path\":\"1.png\"}]}", null, false);
    }

    private boolean isInside(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }

    private void awaitCount(FavoriteActivity activity, int count) throws Exception {
        androidx.recyclerview.widget.RecyclerView recycler = activity.findViewById(R.id.recycler);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while ((recycler.getAdapter().getItemCount() != count || activity.getRefresher().isRefreshing()) && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(count, recycler.getAdapter().getItemCount());
        assertFalse(activity.getRefresher().isRefreshing());
    }
}
