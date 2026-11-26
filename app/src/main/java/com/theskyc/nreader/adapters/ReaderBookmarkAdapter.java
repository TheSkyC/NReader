// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
        return new Holder(LayoutInflater.from(parent.getContext())
            .inflate(R.layout.reader_bookmark_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ReaderBookmark bookmark = bookmarks.get(position);
        holder.title.setText(bookmark.title);
        holder.page.setText(holder.itemView.getContext().getString(R.string.bookmark_page_format, bookmark.page));
        holder.note.setText(bookmark.note);
        holder.note.setVisibility(bookmark.note.isEmpty() ? View.GONE : View.VISIBLE);
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
        final View open;
        final View remove;
        final TextView title;
        final TextView page;
        final TextView note;

        Holder(@NonNull View row) {
            super(row);
            open = row.findViewById(R.id.reader_bookmark_open);
            remove = row.findViewById(R.id.reader_bookmark_remove);
            title = row.findViewById(R.id.reader_bookmark_item_title);
            page = row.findViewById(R.id.reader_bookmark_item_page);
            note = row.findViewById(R.id.reader_bookmark_item_note);
        }
    }
}
