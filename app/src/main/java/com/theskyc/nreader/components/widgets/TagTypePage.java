// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.widgets;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.R;
import com.theskyc.nreader.TagFilterActivity;
import com.theskyc.nreader.adapters.TagsAdapter;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.ScrapeTags;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagRuleEdits;
import com.theskyc.nreader.loginapi.LoadTags;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Login;

import java.util.Objects;

public class TagTypePage extends Fragment {
    private TagType type;
    private RecyclerView recyclerView;
    private TextView rulesEmpty;
    private TagFilterActivity activity;
    private String query;
    private TagsAdapter adapter;
    private String adapterIdentity;
    private int requestedColumns;
    private int minimumColumnWidth;
    private int layoutOrientation;
    private float layoutFontScale;
    private long refreshSequence;
    private long observedRestoreGeneration;
    private boolean waitingForOnlineTags;
    private final Runnable onlineTagsObserver = () -> {
        View root = getView();
        if (root == null || adapter == null) return;
        long restoreGeneration = Login.onlineTagsRestoreGeneration();
        if ((waitingForOnlineTags || restoreGeneration != observedRestoreGeneration) && !Login.isOnlineTagMutationPending()) {
            waitingForOnlineTags = false;
            observedRestoreGeneration = restoreGeneration;
            if (!Login.hasCurrentOnlineTags() && AuthStore.activeIdentity(requireContext()) != null) {
                refreshOnlineTags(root);
                return;
            }
            root.findViewById(R.id.online_tag_refresh).setEnabled(true);
            root.findViewById(R.id.online_tag_progress).setVisibility(View.GONE);
        }
        TextView status = root.findViewById(R.id.online_tag_status);
        status.setText(Login.isOnlineTagMutationPending() ? R.string.online_blacklist_loading
            : Login.hasCurrentOnlineTags() ? R.string.online_blacklist_loaded : R.string.online_blacklist_load_error);
        adapter.filter(query);
    };
    private final Runnable authObserver = () -> {
        Activity host = getActivity();
        if (host != null) host.runOnUiThread(() -> {
            View root = getView();
            if (root == null || adapter == null) return;
            String currentIdentity = AuthStore.activeIdentity(host);
            if (!Objects.equals(adapterIdentity, currentIdentity)) {
                refreshSequence++;
                loadTags();
                if (currentIdentity != null) refreshOnlineTags(root);
                else {
                    root.findViewById(R.id.online_tag_refresh).setEnabled(true);
                    root.findViewById(R.id.online_tag_progress).setVisibility(View.GONE);
                    onlineTagsObserver.run();
                }
            }
        });
    };

    public TagTypePage() {
    }

    private static int getTag(int page) {
        switch (page) {
            case 0:
                return TagType.UNKNOWN.getId();//tags with status
            case 1:
                return TagType.TAG.getId();
            case 2:
                return TagType.ARTIST.getId();
            case 3:
                return TagType.CHARACTER.getId();
            case 4:
                return TagType.PARODY.getId();
            case 5:
                return TagType.GROUP.getId();
            case 6:
                return TagType.CATEGORY.getId();//online blacklisted tags
        }
        return -1;
    }

    public static TagTypePage newInstance(int page) {
        TagTypePage fragment = new TagTypePage();
        Bundle args = new Bundle();
        args.putInt("TAGTYPE", getTag(page));
        fragment.setArguments(args);
        return fragment;
    }

    public RecyclerView getRecyclerView() {
        return recyclerView;
    }

    @Nullable
    public TagType getTagType() {
        return type;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        activity = (TagFilterActivity) getActivity();
        type = TagType.values[requireArguments().getInt("TAGTYPE")];
        View rootView = inflater.inflate(R.layout.fragment_tag_filter, container, false);
        recyclerView = rootView.findViewById(R.id.recycler);
        rulesEmpty = rootView.findViewById(R.id.tag_rules_empty);
        loadTags();
        Global.applyFastScroller(recyclerView);
        if (type.equals(TagType.CATEGORY)) {
            observedRestoreGeneration = Login.onlineTagsRestoreGeneration();
            Login.observeOnlineTags(onlineTagsObserver);
            AuthStore.observeIdentityChanges(authObserver);
            rootView.findViewById(R.id.online_tag_sync).setVisibility(View.VISIBLE);
            rootView.findViewById(R.id.online_tag_refresh).setOnClickListener(view -> refreshOnlineTags(rootView));
            refreshOnlineTags(rootView);
        }
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.addOnLayoutChangeListener((changedView, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateResponsiveGrid(changedView));
        TagCatalog.changes().observe(getViewLifecycleOwner(), ignored -> refilter(query));
        TagTranslationCatalog.changes().observe(getViewLifecycleOwner(), ignored -> refilter(query));
        if (type != TagType.CATEGORY) {
            TagRuleEdits.changes().observe(getViewLifecycleOwner(), ignored -> refilter(query));
        }
    }

    private void refreshOnlineTags(View root) {
        long request = ++refreshSequence;
        View refresh = root.findViewById(R.id.online_tag_refresh);
        View progress = root.findViewById(R.id.online_tag_progress);
        TextView status = root.findViewById(R.id.online_tag_status);
        refresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.online_blacklist_loading);
        if (Login.isOnlineTagMutationPending()) {
            waitingForOnlineTags = true;
            return;
        }
        waitingForOnlineTags = false;
        long startingRevision = Login.currentOnlineTagsRevision();
        long startingInvalidation = Login.onlineTagsInvalidationSequence();
        startOnlineTagsLoad(successful -> {
            Activity host = getActivity();
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (getView() != root || adapter == null || request != refreshSequence) return;
                if (Login.isOnlineTagMutationPending()) {
                    waitingForOnlineTags = true;
                    status.setText(R.string.online_blacklist_loading);
                    return;
                }
                if (startingInvalidation != Login.onlineTagsInvalidationSequence()
                    && !Login.hasCurrentOnlineTags() && AuthStore.activeIdentity(host) != null) {
                    refreshOnlineTags(root);
                    return;
                }
                refresh.setEnabled(true);
                progress.setVisibility(View.GONE);
                long currentRevision = Login.currentOnlineTagsRevision();
                status.setText(Login.isOnlineTagMutationPending() ? R.string.online_blacklist_loading
                    : refreshHasCurrentData(successful, startingRevision, currentRevision)
                        ? R.string.online_blacklist_loaded : R.string.online_blacklist_load_error);
                adapter.filter(query);
            });
        });
    }

    void startOnlineTagsLoad(LoadTags.Completion completion) {
        new LoadTags(requireContext(), completion).start();
    }

    static boolean refreshHasCurrentData(boolean successful, long startingRevision, long currentRevision) {
        return currentRevision >= 0 && (successful || currentRevision != startingRevision);
    }

    public void loadTags() {
        if (adapter != null) adapter.close();
        query = activity.getSearchQuery();
        if (type.equals(TagType.CATEGORY)) adapterIdentity = AuthStore.activeIdentity(activity);
        Configuration configuration = getResources().getConfiguration();
        layoutOrientation = configuration.orientation;
        layoutFontScale = configuration.fontScale;
        CustomGridLayoutManager layout = new CustomGridLayoutManager(activity,
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 2);
        requestedColumns = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 2;
        minimumColumnWidth = Math.round(getResources().getDimension(R.dimen.tag_min_column_width)
            * Math.max(1f, configuration.fontScale));
        layout.setMinimumColumnWidth(minimumColumnWidth);
        recyclerView.setLayoutManager(layout);
        if (type.equals(TagType.UNKNOWN)) adapter = new TagsAdapter(activity, query, false);
        else if (type.equals(TagType.CATEGORY)) adapter = new TagsAdapter(activity, query, true);
        else adapter = new TagsAdapter(activity, query, type);
        recyclerView.setAdapter(adapter);
        if (type != TagType.CATEGORY) adapter.setResultsChangedListener(this::updateEmptyMessage);
    }

    private void updateEmptyMessage() {
        if (rulesEmpty == null || adapter == null) return;
        boolean searching = query != null && !query.trim().isEmpty();
        boolean empty = adapter.areResultsReady() && adapter.getItemCount() == 0
            && (type == TagType.UNKNOWN || searching);
        rulesEmpty.setText(type == TagType.UNKNOWN
            ? searching ? R.string.tag_rules_no_matches : R.string.tag_rules_empty
            : R.string.bulk_edit_no_matches);
        rulesEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    public void refilter(String newText) {
        query = newText;
        if (activity != null && adapter != null) adapter.filter(query);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutOrientation = newConfig.orientation;
        layoutFontScale = newConfig.fontScale;
        View root = getView();
        if (root != null) updateResponsiveGrid(root);
    }

    void updateResponsiveGrid(@NonNull View root) {
        if (recyclerView == null || !(recyclerView.getLayoutManager() instanceof CustomGridLayoutManager)) return;
        boolean landscape = layoutOrientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean compact = landscape && root.getHeight() > 0
            && root.getHeight() < getResources().getDimensionPixelSize(R.dimen.tag_search_compact_height);
        CustomGridLayoutManager layout = (CustomGridLayoutManager) recyclerView.getLayoutManager();
        int columns = compact ? 1 : landscape ? 4 : 2;
        if (requestedColumns != columns) {
            requestedColumns = columns;
            layout.setRequestedColumnCount(columns);
        }
        int minWidth = Math.round(getResources().getDimension(R.dimen.tag_min_column_width)
            * Math.max(1f, layoutFontScale));
        if (minimumColumnWidth != minWidth) {
            minimumColumnWidth = minWidth;
            layout.setMinimumColumnWidth(minWidth);
        }
        if (adapter != null) adapter.setCompactRows(compact);
    }

    @Override
    public void onDestroyView() {
        refreshSequence++;
        waitingForOnlineTags = false;
        Login.removeOnlineTagsObserver(onlineTagsObserver);
        AuthStore.removeIdentityObserver(authObserver);
        if (adapter != null) adapter.close();
        if (recyclerView != null) recyclerView.setAdapter(null);
        adapter = null;
        recyclerView = null;
        rulesEmpty = null;
        super.onDestroyView();
    }

    public void reset() {
        if (type.equals(TagType.UNKNOWN)) {
            View root = getView();
            TagRuleEdits.clear(result -> {
                Activity host = getActivity();
                if (host == null || host.isFinishing() || getView() != root) return;
                if (result.outcome != TagRuleEdits.Outcome.SAVED) {
                    Toast.makeText(host, R.string.local_tag_rule_save_error, Toast.LENGTH_SHORT).show();
                }
            });
        } else if (!type.equals(TagType.CATEGORY)) {
            ScrapeTags.startWork(activity);
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    public void changeSize() {
        refilter(query);
    }
}

