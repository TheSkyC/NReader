// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.TagFilterActivity;
import com.maxwai.nclientv3.adapters.TagsAdapter;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.ScrapeTags;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.TagV2;

import java.util.Objects;

public class TagTypePage extends Fragment {
    private TagType type;
    private RecyclerView recyclerView;
    private TagFilterActivity activity;
    private String query;
    private TagsAdapter adapter;
    private String adapterIdentity;
    private long refreshSequence;
    private final Runnable onlineTagsObserver = () -> {
        View root = getView();
        if (root == null || adapter == null) return;
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

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        activity = (TagFilterActivity) getActivity();
        type = TagType.values[requireArguments().getInt("TAGTYPE")];
        View rootView = inflater.inflate(R.layout.fragment_tag_filter, container, false);
        recyclerView = rootView.findViewById(R.id.recycler);
        Global.applyFastScroller(recyclerView);
        loadTags();
        if (type.equals(TagType.CATEGORY)) {
            Login.observeOnlineTags(onlineTagsObserver);
            AuthStore.observeIdentityChanges(authObserver);
            rootView.findViewById(R.id.online_tag_sync).setVisibility(View.VISIBLE);
            rootView.findViewById(R.id.online_tag_refresh).setOnClickListener(view -> refreshOnlineTags(rootView));
            refreshOnlineTags(rootView);
        }
        return rootView;
    }

    private void refreshOnlineTags(View root) {
        long request = ++refreshSequence;
        View refresh = root.findViewById(R.id.online_tag_refresh);
        View progress = root.findViewById(R.id.online_tag_progress);
        TextView status = root.findViewById(R.id.online_tag_status);
        refresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.online_blacklist_loading);
        long startingRevision = Login.currentOnlineTagsRevision();
        startOnlineTagsLoad(successful -> {
            Activity host = getActivity();
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (getView() != root || adapter == null || request != refreshSequence) return;
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
        recyclerView.setLayoutManager(new CustomGridLayoutManager(activity, getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 2));
        if (type.equals(TagType.UNKNOWN)) adapter = new TagsAdapter(activity, query, false);
        else if (type.equals(TagType.CATEGORY)) adapter = new TagsAdapter(activity, query, true);
        else adapter = new TagsAdapter(activity, query, type);
        recyclerView.setAdapter(adapter);
    }

    public void refilter(String newText) {
        query = newText;
        if (activity != null && adapter != null) adapter.filter(query);
    }

    @Override
    public void onDestroyView() {
        refreshSequence++;
        Login.removeOnlineTagsObserver(onlineTagsObserver);
        AuthStore.removeIdentityObserver(authObserver);
        if (adapter != null) adapter.close();
        if (recyclerView != null) recyclerView.setAdapter(null);
        adapter = null;
        recyclerView = null;
        super.onDestroyView();
    }

    public void reset() {
        if (type.equals(TagType.UNKNOWN)) TagV2.resetAllStatus();
        else if (!type.equals(TagType.CATEGORY)) {
            ScrapeTags.startWork(activity);
        }
        Activity activity = getActivity();
        if (activity == null || adapter == null) return;
        activity.runOnUiThread(adapter::notifyDataSetChanged);

    }

    public void changeSize() {
        refilter(query);
    }
}

