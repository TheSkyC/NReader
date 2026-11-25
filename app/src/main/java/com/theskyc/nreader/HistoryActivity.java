package com.theskyc.nreader;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.adapters.ListAdapter;
import com.theskyc.nreader.api.GalleryDetailRequests;
import com.theskyc.nreader.api.SimpleGallery;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.activities.BaseActivity;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.Utility;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class HistoryActivity extends BaseActivity {
    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    ListAdapter adapter;
    private final ArrayDeque<SimpleGallery> pendingMetadata = new ArrayDeque<>();
    private final Set<Integer> requestedMetadata = new HashSet<>();
    private GalleryDetailRequests.Subscription metadataRequest;
    private boolean metadataLoading;
    private boolean historyCleared;

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
        actionBar.setTitle(R.string.history);
        recycler = findViewById(R.id.recycler);
        masterLayout = findViewById(R.id.master_layout);
        adapter = new ListAdapter(this);
        adapter.addGalleries(new ArrayList<>(Queries.HistoryTable.getHistory()));
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }
        });
        updateEmptyState();
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        recycler.setAdapter(adapter);
    }

    private void updateEmptyState() {
        findViewById(R.id.history_empty).setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.cancelAll) {
            historyCleared = true;
            pendingMetadata.clear();
            if (metadataRequest != null) metadataRequest.cancelRequest();
            Queries.HistoryTable.emptyHistory();
            adapter.restartDataset(new ArrayList<>(1));
            return true;

        }
        return super.onOptionsItemSelected(item);
    }

    public void resolveMetadata(SimpleGallery gallery) {
        if (historyCleared || gallery.getId() <= 0 || Global.getDownloadPolicy() == Global.DataUsageType.NONE
            || !requestedMetadata.add(gallery.getId())) return;
        pendingMetadata.add(gallery);
        loadNextMetadata();
    }

    private void loadNextMetadata() {
        if (metadataLoading || historyCleared || isFinishing() || isDestroyed()) return;
        SimpleGallery target = pendingMetadata.poll();
        if (target == null) return;
        metadataLoading = true;
        metadataRequest = GalleryDetailRequests.open(this, target.getId(), new GalleryDetailRequests.Callback() {
            @Override public void onSuccess(List<GenericGallery> galleries) {
                if (galleries.size() != 1 || !(galleries.get(0) instanceof Gallery)
                    || galleries.get(0).getId() != target.getId()) return;
                SimpleGallery resolved = ((Gallery) galleries.get(0)).toSimpleGallery();
                runOnUiThread(() -> {
                    if (historyCleared || isFinishing() || isDestroyed()) return;
                    target.applyHistoryMetadata(resolved);
                    Queries.HistoryTable.updateMetadata(resolved);
                    adapter.updateColor(target.getId());
                });
            }

            @Override public void onRelated(List<SimpleGallery> related) { }
            @Override public void onFailure(Exception error) { }
            @Override public void onEnd() {
                runOnUiThread(() -> {
                    metadataRequest = null;
                    metadataLoading = false;
                    loadNextMetadata();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (metadataRequest != null) metadataRequest.cancelRequest();
        pendingMetadata.clear();
        super.onDestroy();
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortHistory();
    }

    @Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandHistory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.history, menu);
        Utility.tintMenu(this, menu);
        return true;
    }
}
