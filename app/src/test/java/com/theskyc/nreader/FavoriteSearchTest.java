// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewParent;

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
