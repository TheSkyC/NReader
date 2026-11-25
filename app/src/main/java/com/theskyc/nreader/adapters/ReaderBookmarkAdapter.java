// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.adapters;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import com.theskyc.nreader.R;
import com.theskyc.nreader.components.classes.ReaderBookmark;

import java.util.List;
import java.util.function.Consumer;

public final class ReaderBookmarkAdapter extends RecyclerView.Adapter<ReaderBookmarkAdapter.Holder> {
    private final List<ReaderBookmark> bookmarks;
    private final Consumer<ReaderBookmark> onOpen;
    private final Consumer<ReaderBookmark> onDelete;

    public ReaderBookmarkAdapter(@NonNull List<ReaderBookmark> bookmarks,
                                 @NonNull Consumer<ReaderBookmark> onOpen,
                                 @NonNull Consumer<ReaderBookmark> onDelete) {
        this.bookmarks = bookmarks;
        this.onOpen = onOpen;
        this.onDelete = onDelete;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MaterialButton open = textButton(parent);
        open.setAllCaps(false);
        open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton remove = textButton(parent);
        remove.setText(R.string.reader_bookmark_delete);
        row.addView(remove);
        return new Holder(row, open, remove);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ReaderBookmark bookmark = bookmarks.get(position);
        String label = bookmark.title + "  ·  "
            + holder.itemView.getContext().getString(R.string.bookmark_page_format, bookmark.page)
            + (bookmark.note.isEmpty() ? "" : "\n" + bookmark.note);
        holder.open.setText(label);
        holder.remove.setContentDescription(holder.itemView.getContext().getString(
            R.string.reader_bookmark_delete_page, bookmark.page));
        holder.open.setOnClickListener(view -> onOpen.accept(bookmark));
        holder.remove.setOnClickListener(view -> onDelete.accept(bookmark));
    }

    @Override
    public int getItemCount() {
        return bookmarks.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialButton open;
        final MaterialButton remove;

        Holder(@NonNull View row, @NonNull MaterialButton open, @NonNull MaterialButton remove) {
            super(row);
            this.open = open;
            this.remove = remove;
        }
    }

    @NonNull
    private static MaterialButton textButton(@NonNull ViewGroup parent) {
        MaterialButton button = new MaterialButton(parent.getContext());
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary));
        button.setMinHeight(Math.round(48 * parent.getResources().getDisplayMetrics().density));
        return button;
    }
}
