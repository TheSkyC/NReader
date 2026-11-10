// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.FavoriteActivity;
import com.maxwai.nclientv3.GalleryActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.AsyncCursorQuery;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import android.widget.Toast;

public class FavoriteAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> {
    private final int perPage = FavoriteActivity.getEntryPerPage();
    private final FavoriteActivity activity;
    private Gallery[] galleries;
    private String lastQuery;
    private Cursor cursor;
    private final AsyncCursorQuery queryLoader = new AsyncCursorQuery();
    private boolean sortByTitle = false;

    public FavoriteAdapter(FavoriteActivity activity) {
        this.activity = activity;
        this.lastQuery = "";
        setHasStableIds(true);
    }

    @SuppressLint("Range")
    @Override
    public long getItemId(int position) {
        cursor.moveToPosition(position);
        return cursor.getInt(cursor.getColumnIndex(Queries.GalleryTable.IDGALLERY));
    }

    @NonNull
    @Override
    public GenericAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new GenericAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_layout, parent, false));
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recycler) {
        super.onAttachedToRecyclerView(recycler);
        GenericAdapter.prepareGalleryGrid(recycler);
    }

    @Nullable
    private Gallery galleryFromPosition(int position) {
        if (galleries[position] != null) return galleries[position];
        cursor.moveToPosition(position);
        Gallery g = Queries.GalleryTable.cursorToGallery(activity, cursor);
        galleries[position] = g;
        if (g.getGalleryData().hasUpdatedInfo()) { // TODO: to be removed in next major version
            if (g.getGalleryData().isDeleted()) {
                LogUtility.w("Deleting Gallery " + g.getTitle() + " with id " + g.getId() + " since not available anymore");
                Queries.GalleryTable.delete(g.getId());
            } else {
                Queries.GalleryTable.insert(g);
            }
        }
        return g;
    }

    @Override
    public void onBindViewHolder(@NonNull final GenericAdapter.ViewHolder holder, int position) {
        final Gallery ent = galleryFromPosition(position);
        if (ent == null) return;
        ImageDownloadUtility.loadImage(activity, ent.getThumbnail(), holder.imgView);
        holder.pages.setText(String.format(Locale.US, "%d", ent.getPageCount()));
        holder.title.setText(ent.getTitle());
        holder.flag.setText(Global.getLanguageFlag(ent.getLanguage()));
        holder.title.setOnClickListener(v -> {
            Layout layout = holder.title.getLayout();
            if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0)
                holder.title.setMaxLines(7);
            else if (holder.title.getMaxLines() == 7) holder.title.setMaxLines(3);
            else holder.layout.performClick();
        });
        holder.layout.setOnClickListener(v -> {
            if (ent.getGalleryData().isValid())
                startGallery(ent);
        });
        holder.stateBadge.bind(ent);
    }

    private void startGallery(Gallery ent) {
        if (ent.getGalleryData().isIncomplete()) {
            InspectorV3.galleryInspector(activity, ent.getId(), new InspectorV3.DefaultInspectorResponse() {
                @Override
                public void onSuccess(List<GenericGallery> galleries) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (galleries.size() == 1 && galleries.get(0) instanceof Gallery)
                            startGallery((Gallery) galleries.get(0));
                        else Toast.makeText(activity, R.string.no_entry_found, Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onFailure(Exception exception) {
                    LogUtility.w("Unable to load favorite gallery details", exception);
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed())
                            Toast.makeText(activity, R.string.unable_to_connect_to_the_site, Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
            return;
        }
        Intent intent = new Intent(activity, GalleryActivity.class);
        LogUtility.d(ent + "");
        intent.putExtra(activity.getPackageName() + ".GALLERY", ent);
        intent.putExtra(activity.getPackageName() + ".UNKNOWN", true);
        activity.startActivity(intent);
    }

    public void changePage() {
        forceReload();
    }

    @Override
    public int getItemCount() {
        return cursor == null ? 0 : cursor.getCount();
    }

    public void filter(String query) {
        lastQuery = query == null ? "" : query.toLowerCase(Locale.US);
        forceReload();
    }

    public void setSortByTitle(boolean sortByTitle) {
        this.sortByTitle = sortByTitle;
        forceReload();
    }

    public void forceReload() {
        String query = lastQuery;
        boolean sort = sortByTitle;
        int requestedPage = Math.max(1, activity.getActualPage());
        int[] pages = new int[2];
        setRefresh(true);
        queryLoader.load(() -> {
            int count = Queries.FavoriteTable.countFavorite(query);
            pages[0] = Math.max(1, count / perPage + (count % perPage == 0 ? 0 : 1));
            pages[1] = Math.min(requestedPage, pages[0]);
            return Queries.FavoriteTable.getAllFavoriteGalleriesCursor(query, sort, perPage, (pages[1] - 1) * perPage);
        }, result -> {
            int oldSize = getItemCount(), newSize = result.getCount();
            cursor = result;
            galleries = new Gallery[newSize];
            activity.onFavoritesLoaded(pages[0], pages[1]);
            if (oldSize > newSize) notifyItemRangeRemoved(newSize, oldSize - newSize);
            else if (newSize > oldSize) notifyItemRangeInserted(oldSize, newSize - oldSize);
            notifyItemRangeChanged(0, Math.min(newSize, oldSize));
            setRefresh(false);
        }, () -> setRefresh(false));
    }

    public void setRefresh(boolean refresh) {
        activity.runOnUiThread(() -> activity.getRefresher().setRefreshing(refresh));
    }

    public void close() {
        cursor = null;
        galleries = new Gallery[0];
        queryLoader.close();
    }

    public Collection<Gallery> getAllGalleries() {
        if (cursor == null) return Collections.emptyList();
        int count = cursor.getCount();
        ArrayList<Gallery> galleries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) galleries.add(galleryFromPosition(i));
        return galleries;
    }

    public void randomGallery() {
        if (cursor == null || cursor.getCount() < 1) return;
        startGallery(galleryFromPosition(Utility.RANDOM.nextInt(cursor.getCount())));
    }
}
