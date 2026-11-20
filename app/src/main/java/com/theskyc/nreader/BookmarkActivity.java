package com.theskyc.nreader;

import android.os.Bundle;
import android.view.MenuItem;

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
    BookmarkAdapter adapter;
    RecyclerView recycler;

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
        recycler.setAdapter(new ConcatAdapter(new SearchPlanAdapter(this), adapter));
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
