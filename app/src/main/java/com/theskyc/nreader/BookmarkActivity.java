package com.theskyc.nreader;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;

import com.theskyc.nreader.adapters.BookmarkAdapter;
import com.theskyc.nreader.adapters.SearchPlanAdapter;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.widgets.CustomLinearLayoutManager;

import java.util.Objects;

public class BookmarkActivity extends GeneralActivity {
    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    BookmarkAdapter adapter;
    RecyclerView recycler;
    private SearchPlanAdapter plans;
    private ConcatAdapter combinedAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_bookmark);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.manage_bookmarks);

        recycler = findViewById(R.id.recycler);
        adapter = new BookmarkAdapter(this);
        recycler.setLayoutManager(new CustomLinearLayoutManager(this));
        plans = new SearchPlanAdapter(this);
        combinedAdapter = new ConcatAdapter(plans, adapter);
        combinedAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() { updateEmptyState(); }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) { updateEmptyState(); }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) { updateEmptyState(); }
        });
        recycler.setAdapter(combinedAdapter);
        updateEmptyState();
    }

    private void updateEmptyState() {
        TextView message = findViewById(R.id.bookmarks_empty);
        boolean show = plans.isLoadFinished() && combinedAdapter.getItemCount() == 0;
        message.setText(plans.hasLoadFailed() ? R.string.saved_searches_load_failed : R.string.bookmarks_empty);
        message.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
