// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.components.GlideX;
import com.theskyc.nreader.files.GalleryFolder;
import com.theskyc.nreader.files.PageFile;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.DebugImageMask;

import java.util.function.IntConsumer;

public class ReaderPagePreviewAdapter extends RecyclerView.Adapter<ReaderPagePreviewAdapter.Holder> {
    private final GenericGallery gallery;
    @Nullable private final GalleryFolder directory;
    private final int currentPage;
    private final IntConsumer onPageSelected;

    public ReaderPagePreviewAdapter(GenericGallery gallery, @Nullable GalleryFolder directory,
                                    int currentPage, IntConsumer onPageSelected) {
        this.gallery = gallery;
        this.directory = directory;
        this.currentPage = currentPage;
        this.onPageSelected = onPageSelected;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.reader_page_preview_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        boolean current = position == currentPage;
        String label = holder.itemView.getContext().getString(
            current ? R.string.reader_preview_current_page : R.string.bookmark_page_format, position + 1);
        holder.label.setText(label);
        holder.itemView.setContentDescription(label);
        holder.card.setStrokeColor(MaterialColors.getColor(holder.itemView, androidx.appcompat.R.attr.colorPrimary));
        holder.card.setStrokeWidth(current ? Math.round(2 * holder.itemView.getResources().getDisplayMetrics().density) : 0);
        holder.itemView.setOnClickListener(view -> {
            int selected = holder.getBindingAdapterPosition();
            if (selected != RecyclerView.NO_POSITION) onPageSelected.accept(selected);
        });

        RequestManager glide = GlideX.with(holder.image);
        if (glide == null) return;
        glide.clear(holder.image);
        PageFile localPage = directory == null ? null : directory.getPage(position + 1);
        Object source = localPage;
        if (source == null && gallery instanceof Gallery && Global.getDownloadPolicy() != Global.DataUsageType.NONE) {
            source = ((Gallery) gallery).getLowPage(position);
        }
        if (source == null) {
            holder.image.setImageResource(R.drawable.ic_logo);
            return;
        }
        int maxWidth = Math.round(160 * holder.image.getResources().getDisplayMetrics().density);
        int maxHeight = Math.round(144 * holder.image.getResources().getDisplayMetrics().density);
        glide.load(source).override(maxWidth, maxHeight).fitCenter()
            .placeholder(R.drawable.ic_logo).error(R.drawable.ic_logo)
            .into(DebugImageMask.target(holder.image));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        RequestManager glide = GlideX.with(holder.image);
        if (glide != null) glide.clear(holder.image);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return gallery.getPageCount();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView image;
        final TextView label;

        Holder(View view) {
            super(view);
            card = (MaterialCardView) view;
            image = view.findViewById(R.id.reader_preview_image);
            label = view.findViewById(R.id.reader_preview_label);
        }
    }
}
