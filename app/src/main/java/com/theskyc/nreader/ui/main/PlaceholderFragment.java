package com.theskyc.nreader.ui.main;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.theskyc.nreader.R;
import com.theskyc.nreader.StatusViewerActivity;
import com.theskyc.nreader.adapters.StatusViewerAdapter;
import com.theskyc.nreader.components.widgets.CustomGridLayoutManager;
import com.theskyc.nreader.settings.Global;

/**
 * A placeholder fragment containing a simple view.
 */
public class PlaceholderFragment extends Fragment {

    private StatusViewerAdapter adapter = null;
    private RecyclerView recycler;
    private SwipeRefreshLayout refresher;
    private TextView emptyMessage;
    private final RecyclerView.AdapterDataObserver emptyObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            updateEmptyMessage();
        }
    };

    private void updateEmptyMessage() {
        if (emptyMessage == null || adapter == null) return;
        emptyMessage.setText(adapter.hasQuery() ? R.string.status_viewer_no_matches : R.string.status_viewer_empty);
        emptyMessage.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    public static PlaceholderFragment newInstance(String statusName) {
        PlaceholderFragment fragment = new PlaceholderFragment();
        Bundle bundle = new Bundle();
        bundle.putString("STATUS_NAME", statusName);
        fragment.setArguments(bundle);
        return fragment;
    }

    private void updateColumnCount(boolean landscape) {
        recycler.setLayoutManager(new CustomGridLayoutManager(getContext(), getColumnCount(landscape)));
        recycler.setAdapter(adapter);
    }

    private int getColumnCount(boolean landscape) {
        return landscape ? Global.getColLandStatus() : Global.getColPortStatus();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            updateColumnCount(true);
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            updateColumnCount(false);
        }
    }

    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater, ViewGroup container,
        Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_status_viewer, container, false);
        recycler = root.findViewById(R.id.recycler);
        refresher = root.findViewById(R.id.refresher);
        emptyMessage = root.findViewById(R.id.empty_message);
        adapter = new StatusViewerAdapter(getActivity(), requireArguments().getString("STATUS_NAME"));
        adapter.registerAdapterDataObserver(emptyObserver);
        updateEmptyMessage();
        if (getActivity() instanceof StatusViewerActivity) {
            ((StatusViewerActivity) getActivity()).syncFragment(this);
        }
        refresher.setOnRefreshListener(() -> {
            adapter.reloadGalleries();
            refresher.setRefreshing(false);
        });
        updateColumnCount(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        Global.applyFastScroller(recycler);
        return root;
    }

    @Override
    public void onDestroyView() {
        adapter.unregisterAdapterDataObserver(emptyObserver);
        adapter = null;
        emptyMessage = null;
        recycler = null;
        refresher = null;
        super.onDestroyView();
    }

    public void changeQuery(String newQuery) {
        if (adapter != null) adapter.setQuery(newQuery);

    }

    public void changeSort(boolean byTitle) {
        if (adapter != null) adapter.updateSort(byTitle);
    }

    public void reload(String query, boolean sortByTitle) {
        if (adapter != null)
            adapter.update(query, sortByTitle);
    }
}
