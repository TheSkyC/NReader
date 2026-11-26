package com.theskyc.nreader;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.viewpager2.widget.ViewPager2;

import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.components.widgets.LiveSearchResults;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.ui.main.PlaceholderFragment;
import com.theskyc.nreader.ui.main.SectionsPagerAdapter;
import com.theskyc.nreader.utility.LogUtility;
import com.theskyc.nreader.utility.Utility;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatusViewerActivity extends GeneralActivity {
    private static final String STATE_QUERY = "status.search.query";
    private static final String STATE_SEARCH_EXPANDED = "status.search.expanded";
    private static final String STATE_SORT_BY_TITLE = "status.sort_by_title";

    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    private boolean sortByTitle = false;
    private String query;
    private ViewPager2 viewPager;
    private SectionsPagerAdapter sectionsPagerAdapter;
    private List<String> displayedStatuses;
    private SearchBar searchBar;
    private SearchView searchView;
    private final ExecutorService countLoader = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_viewer);
        searchBar = findViewById(R.id.toolbar);
        setSupportActionBar(searchBar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        searchView = findViewById(R.id.search_view);
        searchView.setupWithSearchBar(searchBar);
        LiveSearchResults.attach(searchView, findViewById(R.id.search_results));
        searchView.getEditText().setHint(getString(R.string.search));
        searchView.getEditText().setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        boolean restoreSearchExpanded = false;
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(STATE_QUERY);
            sortByTitle = savedInstanceState.getBoolean(STATE_SORT_BY_TITLE, false);
            restoreSearchExpanded = savedInstanceState.getBoolean(STATE_SEARCH_EXPANDED, false);
            if (query != null) setSearchText(query);
        }
        searchView.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                searchBar.setText(query);
                PlaceholderFragment fragment = getActualFragment();
                if (fragment != null) fragment.changeQuery(query);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        viewPager = findViewById(R.id.view_pager);
        sectionsPagerAdapter = new SectionsPagerAdapter(this);
        displayedStatuses = StatusManager.getNames();

        viewPager.setAdapter(sectionsPagerAdapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {

            @Override
            public void onPageSelected(int position) {
                PlaceholderFragment fragment = getPositionFragment(position);
                if (fragment != null) fragment.reload(query, sortByTitle);
            }
        });

        TabLayout tabs = findViewById(R.id.tabs);
        boolean noStatuses = sectionsPagerAdapter.getItemCount() == 0;
        tabs.setVisibility(noStatuses ? View.GONE : View.VISIBLE);
        findViewById(R.id.no_statuses).setVisibility(noStatuses ? View.VISIBLE : View.GONE);
        findViewById(R.id.manage_statuses_action).setOnClickListener(view ->
            startActivity(new Intent(this, StatusManagerActivity.class)));
        new TabLayoutMediator(tabs, viewPager, true, (tab, position) -> tab.setText(sectionsPagerAdapter.getPageTitle(position))).attach();
        loadStatusCounts();
        if (restoreSearchExpanded) searchView.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!displayedStatuses.equals(StatusManager.getNames())) {
            recreate();
            return;
        }
        loadStatusCounts();
        PlaceholderFragment fragment = getActualFragment();
        if (fragment != null) fragment.reload(query, sortByTitle);
    }

    private void loadStatusCounts() {
        countLoader.execute(() -> {
            int[] counts = new int[sectionsPagerAdapter.getItemCount()];
            for (int i = 0; i < counts.length; i++)
                counts[i] = com.theskyc.nreader.async.database.Queries.StatusMangaTable
                    .getCountPerStatus(sectionsPagerAdapter.getStatus(i));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                TabLayout tabs = findViewById(R.id.tabs);
                for (int i = 0; i < counts.length && i < tabs.getTabCount(); i++) {
                    sectionsPagerAdapter.setCount(i, counts[i]);
                    Objects.requireNonNull(tabs.getTabAt(i)).setText(sectionsPagerAdapter.getPageTitle(i));
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        countLoader.shutdownNow();
        super.onDestroy();
    }

    private void setSearchText(String query) {
        searchBar.setText(query);
        searchView.setText(query);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUERY, searchView.getEditText().getText().toString());
        outState.putBoolean(STATE_SEARCH_EXPANDED, searchView.isShowing());
        outState.putBoolean(STATE_SORT_BY_TITLE, sortByTitle);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.sort_by_name) {
            sortByTitle = !sortByTitle;
            PlaceholderFragment fragment = getActualFragment();
            if (fragment != null) fragment.changeSort(sortByTitle);
            item.setTitle(sortByTitle ? R.string.sort_by_latest : R.string.sort_by_title);
            item.setIcon(sortByTitle ? R.drawable.ic_sort_by_alpha : R.drawable.ic_access_time);
            Global.setTint(this, item.getIcon());
        }
        return super.onOptionsItemSelected(item);
    }

    @Nullable
    private PlaceholderFragment getActualFragment() {
        return getPositionFragment(viewPager.getCurrentItem());
    }

    @Nullable
    private PlaceholderFragment getPositionFragment(int position) {
        PlaceholderFragment f = (PlaceholderFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
        LogUtility.d(f);
        return f;
    }

    public void syncFragment(PlaceholderFragment fragment) {
        fragment.reload(query, sortByTitle);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.status_viewer, menu);
        MenuItem sortItem = menu.findItem(R.id.sort_by_name);
        sortItem.setTitle(sortByTitle ? R.string.sort_by_latest : R.string.sort_by_title);
        sortItem.setIcon(sortByTitle ? R.drawable.ic_sort_by_alpha : R.drawable.ic_access_time);
        Utility.tintMenu(this, menu);
        return super.onCreateOptionsMenu(menu);
    }
}
