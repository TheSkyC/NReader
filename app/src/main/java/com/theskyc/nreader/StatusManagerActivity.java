package com.theskyc.nreader;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.adapters.StatusManagerAdapter;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.components.widgets.CustomLinearLayoutManager;

import java.util.Objects;

public class StatusManagerActivity extends GeneralActivity {

    StatusManagerAdapter adapter;
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
        actionBar.setTitle(R.string.manage_statuses);

        recycler = findViewById(R.id.recycler);
        adapter = new StatusManagerAdapter(this);
        recycler.setLayoutManager(new CustomLinearLayoutManager(this));
        recycler.setAdapter(adapter);
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
