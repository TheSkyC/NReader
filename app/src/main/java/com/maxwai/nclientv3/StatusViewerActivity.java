package com.maxwai.nclientv3;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.ui.main.PlaceholderFragment;
import com.maxwai.nclientv3.ui.main.SectionsPagerAdapter;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatusViewerActivity extends GeneralActivity {
    private boolean sortByTitle = false;
    private String query;
    private ViewPager2 viewPager;
    private SectionsPagerAdapter sectionsPagerAdapter;
    private final ExecutorService countLoader = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status_viewer);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.manage_statuses);
        viewPager = findViewById(R.id.view_pager);
        sectionsPagerAdapter = new SectionsPagerAdapter(this);

        viewPager.setAdapter(sectionsPagerAdapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {

            @Override
            public void onPageSelected(int position) {
                PlaceholderFragment fragment = getPositionFragment(position);
                if (fragment != null) fragment.reload(query, sortByTitle);
            }
        });

        TabLayout tabs = findViewById(R.id.tabs);
        new TabLayoutMediator(tabs, viewPager, true, (tab, position) -> tab.setText(sectionsPagerAdapter.getPageTitle(position))).attach();
        loadStatusCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStatusCounts();
        PlaceholderFragment fragment = getActualFragment();
        if (fragment != null) fragment.reload(query, sortByTitle);
    }

    private void loadStatusCounts() {
        countLoader.execute(() -> {
            int[] counts = new int[sectionsPagerAdapter.getItemCount()];
            for (int i = 0; i < counts.length; i++)
                counts[i] = com.maxwai.nclientv3.async.database.Queries.StatusMangaTable
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.status_viewer, menu);
        final SearchView searchView = (androidx.appcompat.widget.SearchView) menu.findItem(R.id.search).getActionView();
        Objects.requireNonNull(searchView).setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                query = newText;
                PlaceholderFragment fragment = getActualFragment();
                if (fragment != null) fragment.changeQuery(query);
                return true;
            }
        });
        Utility.tintMenu(this, menu);
        return super.onCreateOptionsMenu(menu);
    }
}
