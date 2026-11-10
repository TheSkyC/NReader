// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.Intent;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.GalleryActivity;
import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.files.LocalDownloadState;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.TagV2;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LanguageFlag;
import com.maxwai.nclientv3.utility.LogUtility;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ListAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> {
    private final List<SimpleGallery> mDataset;
    private final List<SimpleGallery> allDataset = new ArrayList<>();
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private final BaseActivity context;
    private final String queryString;
    private RecyclerView attachedRecycler;
    private boolean loadedDownloadedOnly;
    private boolean loadedFavoriteOnly;
    private int loadedReadingFilter;
    private boolean loadedCustomStatusOnly;

    public ListAdapter(BaseActivity cont) {
        this.context = cont;
        this.mDataset = new ArrayList<>() {
            @Override
            public SimpleGallery get(int index) {
                try {
                    return super.get(index);
                } catch (ArrayIndexOutOfBoundsException ignore) {
                    return null;
                }
            }
        };
        queryString = TagV2.getAvoidedTags();
    }

    @NonNull
    @Override
    public GenericAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new GenericAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_layout, parent, false));
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recycler) {
        super.onAttachedToRecyclerView(recycler);
        attachedRecycler = recycler;
        GenericAdapter.prepareGalleryGrid(recycler);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recycler) {
        if (attachedRecycler == recycler) attachedRecycler = null;
        super.onDetachedFromRecyclerView(recycler);
    }

    private void loadGallery(final GenericAdapter.ViewHolder holder, SimpleGallery ent) {
        if (context.isFinishing()) return;
        try {
            if (Global.isDestroyed(context)) return;

            ImageDownloadUtility.loadImage(context, ent.getThumbnail(), holder.imgView);
        } catch (VerifyError ignore) {
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final GenericAdapter.ViewHolder holder, int position) {
        int holderPos = position;
        if (holderPos < 0 || holderPos >= mDataset.size()) return;
        final SimpleGallery ent = mDataset.get(holderPos);
        if (ent == null) return;
        if (!Global.showTitles()) {
            holder.title.setAlpha(0f);
            holder.flag.setAlpha(0f);
        } else {
            holder.title.setAlpha(1f);
            holder.flag.setAlpha(1f);
        }
        if (context instanceof GalleryActivity) {
            CardView card = (CardView) holder.layout.getParent();
            ViewGroup.LayoutParams params = card.getLayoutParams();
            RecyclerView parent = attachedRecycler;
            if (parent != null) {
                float density = parent.getResources().getDisplayMetrics().density;
                int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
                if (availableWidth <= 0) availableWidth = parent.getResources().getDisplayMetrics().widthPixels;
                params.width = relatedCardWidth(availableWidth, density);
            }
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            card.setLayoutParams(params);
        }
        holder.overlay.setVisibility((queryString != null && ent.hasIgnoredTags(queryString)) ? View.VISIBLE : View.GONE);
        loadGallery(holder, ent);
        holder.pages.setVisibility(View.GONE);
        holder.title.setText(ent.getTitle());
        holder.flag.setVisibility(View.VISIBLE);
        if (Global.getOnlyLanguage() == Language.ALL || context instanceof GalleryActivity) {
            LanguageFlag.bind(holder.flag, ent.getLanguage());
        } else holder.flag.setVisibility(View.GONE);
        holder.title.setOnClickListener(v -> {
            Layout layout = holder.title.getLayout();
            if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0)
                holder.title.setMaxLines(7);
            else if (holder.title.getMaxLines() == 7) holder.title.setMaxLines(3);
            else holder.layout.performClick();
        });
        holder.layout.setOnClickListener(v -> {
              /*Intent intent = new Intent(context, GalleryActivity.class);
              intent.putExtra(context.getPackageName() + ".ID", ent.getId());
              context.startActivity(intent);*/
            if (context instanceof MainActivity)
                ((MainActivity) context).setIdOpenedGallery(ent.getId());
            downloadGallery(ent);
            holder.overlay.setVisibility((queryString != null && ent.hasIgnoredTags(queryString)) ? View.VISIBLE : View.GONE);
        });
        holder.overlay.setOnClickListener(v -> holder.overlay.setVisibility(View.GONE));
        holder.stateBadge.bind(ent);
    }

    static int relatedCardWidth(int availableWidth, float density) {
        int halfWidth = (availableWidth - Math.round(24 * density)) / 2;
        return Math.max(Math.round(116 * density), Math.min(halfWidth, Math.round(200 * density)));
    }

    public void updateColor(int id) {
        if (id < 0) return;
        int position = -1;
        for (int i = 0; i < mDataset.size(); i++) {
            SimpleGallery gallery= mDataset.get(i);
            if (gallery != null && gallery.getId() == id) {
                position = i;
                break;
            }
        }
        if (position >= 0) notifyItemChanged(position);
    }

    private void downloadGallery(final SimpleGallery ent) {
        InspectorV3.galleryInspector(context, ent.getId(), new InspectorV3.DefaultInspectorResponse() {
            @Override
            public void onFailure(Exception e) {
                super.onFailure(e);
                File file = Global.findGalleryFolder(context, ent.getId());
                if (file != null) {
                    LocalAdapter.startGallery(context, file);
                } else if (context.getMasterLayout() != null) {
                    context.runOnUiThread(() -> {
                            Snackbar snackbar = Snackbar.make(context.getMasterLayout(), R.string.unable_to_connect_to_the_site, Snackbar.LENGTH_SHORT);
                            snackbar.setAction(R.string.retry, v -> downloadGallery(ent));
                            snackbar.show();
                        }
                    );
                }
            }

            @Override
            public void onSuccess(List<GenericGallery> galleries) {
                if (galleries.size() != 1) {
                    if (context.getMasterLayout() != null) {
                        context.runOnUiThread(() ->
                            Snackbar.make(context.getMasterLayout(), R.string.no_entry_found, Snackbar.LENGTH_SHORT).show()
                        );
                    }
                    return;
                }
                Intent intent = new Intent(context, GalleryActivity.class);
                LogUtility.d(galleries.get(0).toString());
                intent.putExtra(context.getPackageName() + ".GALLERY", galleries.get(0));
                context.runOnUiThread(() -> context.startActivity(intent));
            }
        }).start();
    }


    @Override
    public int getItemCount() {
        return mDataset == null ? 0 : mDataset.size();
    }

    public void setLoadedFilters(boolean downloadedOnly, boolean favoriteOnly, int readingFilter, boolean customStatusOnly) {
        loadedDownloadedOnly = downloadedOnly;
        loadedFavoriteOnly = favoriteOnly;
        loadedReadingFilter = readingFilter;
        loadedCustomStatusOnly = customStatusOnly;
        applyLoadedFilters();
    }

    public boolean isLoadedDownloadedOnly() { return loadedDownloadedOnly; }
    public boolean isLoadedFavoriteOnly() { return loadedFavoriteOnly; }
    public int getLoadedReadingFilter() { return loadedReadingFilter; }
    public boolean isLoadedCustomStatusOnly() { return loadedCustomStatusOnly; }

    private void applyLoadedFilters() {
        List<SimpleGallery> snapshot = new ArrayList<>(allDataset);
        filterExecutor.execute(() -> {
            ArrayList<SimpleGallery> result = new ArrayList<>();
            for (SimpleGallery gallery : snapshot) if (matchesLoadedFilters(gallery)) result.add(gallery);
            context.runOnUiThread(() -> {
                mDataset.clear();
                mDataset.addAll(result);
                notifyDataSetChanged();
            });
        });
    }

    private boolean matchesLoadedFilters(SimpleGallery gallery) {
        int id = gallery.getId();
        File folder = Global.findGalleryFolder(context, id);
        if (loadedDownloadedOnly && folder == null) return false;
        if (loadedFavoriteOnly && !Queries.FavoriteTable.isFavorite(id)) return false;
        boolean reading = Queries.ReadingProgressTable.fromId(id) != null;
        if (loadedReadingFilter == 1 && !reading) return false;
        if (loadedReadingFilter == 2 && reading) return false;
        if (loadedCustomStatusOnly && com.maxwai.nclientv3.components.status.StatusManager.DEFAULT_STATUS
            .equals(Queries.StatusMangaTable.getStatus(id).name)) return false;
        return true;
    }

    public void addGalleries(List<GenericGallery> galleries) {
        int c = mDataset.size();
        for (GenericGallery g : galleries) {
            SimpleGallery gallery = (SimpleGallery) g;
            allDataset.add(gallery);
            if (!loadedDownloadedOnly && !loadedFavoriteOnly && loadedReadingFilter == 0 && !loadedCustomStatusOnly)
                mDataset.add(gallery);

            LogUtility.d("Simple: " + g);
        }
        LogUtility.d(String.format(Locale.US, "%s,old:%d,new:%d,len%d", this, c, mDataset.size(), galleries.size()));
        if (loadedDownloadedOnly || loadedFavoriteOnly || loadedReadingFilter != 0 || loadedCustomStatusOnly) applyLoadedFilters();
        else context.runOnUiThread(() -> notifyItemRangeInserted(c, galleries.size()));
    }

    public void restartDataset(List<GenericGallery> galleries) {
        /*int c=mDataset.size();
        if(c>0) {
            mDataset.clear();
            context.runOnUiThread(() -> notifyItemRangeRemoved(0, c));
        }
        mDataset.addAll(galleries);
        context.runOnUiThread(()->notifyItemRangeInserted(0,galleries.size()));*/
        mDataset.clear();
        allDataset.clear();
        for (GenericGallery g : galleries)
            if (g instanceof SimpleGallery)
                allDataset.add((SimpleGallery) g);
        if (loadedDownloadedOnly || loadedFavoriteOnly || loadedReadingFilter != 0 || loadedCustomStatusOnly) {
            applyLoadedFilters();
            return;
        }
        mDataset.addAll(allDataset);
        context.runOnUiThread(this::notifyDataSetChanged);
    }


    public void resetStatuses() {
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidate();
    }

    public void close() {
        filterExecutor.shutdownNow();
    }
}
