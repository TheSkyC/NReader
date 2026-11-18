// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.components.classes.ReaderBookmark;

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
        Button open = new Button(parent.getContext());
        open.setAllCaps(false);
        row.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button remove = new Button(parent.getContext());
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
        holder.open.setOnClickListener(view -> onOpen.accept(bookmark));
        holder.remove.setOnClickListener(view -> onDelete.accept(bookmark));
    }

    @Override
    public int getItemCount() {
        return bookmarks.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final Button open;
        final Button remove;

        Holder(@NonNull View row, @NonNull Button open, @NonNull Button remove) {
            super(row);
            this.open = open;
            this.remove = remove;
        }
    }
}
