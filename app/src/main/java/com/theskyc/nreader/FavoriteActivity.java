// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.theskyc.nreader.adapters.FavoriteAdapter;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.async.downloader.DownloadGalleryV2;
import com.theskyc.nreader.components.activities.BaseActivity;
import com.theskyc.nreader.components.views.PageSwitcher;
import com.theskyc.nreader.components.widgets.LiveSearchResults;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;

import java.util.Objects;


public class FavoriteActivity extends BaseActivity {
    @Override protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    private static final int ENTRY_PER_PAGE = 24;
    private FavoriteAdapter adapter = null;
    private boolean sortByTitle = false;
    private PageSwitcher pageSwitcher;
    private SearchBar searchBar;
    private SearchView searchView;
    private String query = "";
    private boolean searchExpanded;
    private boolean applyingPages;
    private boolean restoringQuery;

    public static int getEntryPerPage() {
        return Global.isInfiniteScrollFavorite() ? Integer.MAX_VALUE : ENTRY_PER_PAGE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("favorite_query", "");
            searchExpanded = savedInstanceState.getBoolean("favorite_search_expanded");
            sortByTitle = savedInstanceState.getBoolean("favorite_sort_title");
        }
        //Global.initActivity(this);

        setContentView(R.layout.app_bar_favorites);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setVisibility(View.GONE);
        searchBar = findViewById(R.id.favorite_search_bar);
        searchBar.setVisibility(View.VISIBLE);
        searchBar.setHint(R.string.favorite_manga);
        setSupportActionBar(searchBar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setTitle(R.string.favorite_manga);
        searchBar.setNavigationIcon(R.drawable.ic_arrow_back);
        searchBar.setNavigationOnClickListener(view -> finish());
        searchView = findViewById(R.id.favorite_search_view);
        searchView.setupWithSearchBar(searchBar);
        LiveSearchResults.attach(searchView, findViewById(R.id.master_layout));
        searchView.getEditText().setHint(R.string.search);
        pageSwitcher = findViewById(R.id.page_switcher);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        refresher.setRefreshing(true);
        adapter = new FavoriteAdapter(this);


        refresher.setOnRefreshListener(adapter::forceReload);
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        recycler.setAdapter(adapter);
        int page = savedInstanceState == null ? 1 : Math.max(1, savedInstanceState.getInt("favorite_page", 1));
        pageSwitcher.setPages(page, page);
        pageSwitcher.setChanger(new PageSwitcher.DefaultPageChanger() {
            @Override
            public void pageChanged() {
                if (adapter != null && !applyingPages) adapter.changePage();
            }
        });
        searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String newQuery = s.toString();
                if (query.equals(newQuery)) return;
                query = newQuery;
                searchBar.setText(query);
                if (restoringQuery) return;
                applyingPages = true;
                pageSwitcher.setActualPage(1);
                applyingPages = false;
                adapter.filter(query);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        if (!query.isEmpty()) {
            restoringQuery = true;
            searchView.setText(query);
            searchBar.setText(query);
            restoringQuery = false;
        }
        if (searchExpanded) searchView.show();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (searchView.isShowing()) searchView.hide();
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    public int getActualPage() {
        return pageSwitcher.getActualPage();
    }

    @Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandFavorite();
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortFavorite();
    }

    public void onFavoritesLoaded(int totalPages, int page) {
        applyingPages = true;
        pageSwitcher.setPages(totalPages, page);
        applyingPages = false;
    }

    @Override
    protected void onResume() {
        refresher.setEnabled(true);
        refresher.setRefreshing(true);
        adapter.filter(query);
        super.onResume();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("favorite_query", query);
        state.putBoolean("favorite_search_expanded", searchView != null && searchView.isShowing());
        state.putBoolean("favorite_sort_title", sortByTitle);
        state.putInt("favorite_page", getActualPage());
    }

    @Override
    protected void onDestroy() {
        recycler.setAdapter(null);
        adapter.close();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.download_page).setVisible(true);
        menu.findItem(R.id.sort_by_name).setVisible(true);
        menu.findItem(R.id.by_popular).setVisible(false);
        menu.findItem(R.id.only_language).setVisible(false);
        menu.findItem(R.id.add_bookmark).setVisible(false);
        menu.findItem(R.id.search).setVisible(false);

        menu.findItem(R.id.sort_by_name).setTitle(sortByTitle ? R.string.sort_by_latest : R.string.sort_by_title);
        adapter.setSortByTitle(sortByTitle);
        Utility.tintMenu(this, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;
        if (item.getItemId() == R.id.open_browser) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(Utility.getBaseUrl() + "favorites/"));
            startActivity(i);
        } else if (item.getItemId() == R.id.download_page) {
            if (adapter != null) showDialogDownloadAll();
        } else if (item.getItemId() == R.id.sort_by_name) {
            sortByTitle = !sortByTitle;
            adapter.setSortByTitle(sortByTitle);
            item.setTitle(sortByTitle ? R.string.sort_by_latest : R.string.sort_by_title);
        } else if (item.getItemId() == R.id.random_favorite) {
            adapter.randomGallery();
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDialogDownloadAll() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder
            .setTitle(R.string.download_all_galleries_in_this_page)
            .setIcon(R.drawable.ic_file)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                for (Gallery g : adapter.getAllGalleries())
                    DownloadGalleryV2.downloadGallery(this, g);
            });
        builder.show();
    }
}
