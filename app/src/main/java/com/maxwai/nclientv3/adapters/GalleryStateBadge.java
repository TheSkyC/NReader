// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.GalleryState;
import com.maxwai.nclientv3.async.database.GalleryStateRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class GalleryStateBadge implements View.OnAttachStateChangeListener {
    private final TextView label;
    private final TextView title;
    private final int defaultColor;
    private final Runnable refresh = this::refresh;
    private int id;
    private File directory;
    private long binding;

    GalleryStateBadge(View item, TextView title) {
        this.label = item.findViewById(R.id.gallery_state);
        this.title = title;
        defaultColor = title.getCurrentTextColor();
        item.addOnAttachStateChangeListener(this);
    }

    void bind(GenericGallery gallery) {
        id = gallery.getId();
        directory = gallery instanceof LocalGallery ? ((LocalGallery) gallery).getDirectory() : null;
        binding++;
        label.setText("");
        label.setContentDescription(null);
        label.setVisibility(View.GONE);
        title.setBackgroundResource(R.color.entry_background);
        refresh();
    }

    private void refresh() {
        long requestedBinding = ++binding;
        GalleryStateRepository.load(label.getContext(), id, directory, state -> {
            if (binding != requestedBinding) return;
            render(state);
        });
    }

    private void render(GalleryState state) {
        Context context = label.getContext();
        List<String> labels = new ArrayList<>();
        if (state.favorite) labels.add(context.getString(R.string.state_local_favorite));
        if (state.statusName != null) labels.add(context.getString(R.string.state_custom_status, state.statusName));
        if (state.position != null) labels.add(context.getString(R.string.state_reading_page, state.position.page));
        int localLabel = 0;
        switch (state.local) {
            case COMPLETE: localLabel = R.string.state_download_complete; break;
            case PARTIAL: localLabel = R.string.state_download_partial; break;
            case DAMAGED: localLabel = R.string.state_download_damaged; break;
            case UNVERIFIED: localLabel = R.string.state_download_unverified; break;
        }
        if (localLabel != 0) labels.add(context.getString(localLabel));
        String text = android.text.TextUtils.join(" · ", labels);
        label.setText(text);
        label.setContentDescription(text);
        label.setVisibility(labels.isEmpty() ? View.GONE : View.VISIBLE);
        title.setBackgroundColor(state.statusName == null ? context.getColor(R.color.entry_background) : state.statusColor);
        title.setTextColor(defaultColor);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        GalleryStateRepository.observe(refresh);
        refresh();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        binding++;
        GalleryStateRepository.removeObserver(refresh);
    }
}
