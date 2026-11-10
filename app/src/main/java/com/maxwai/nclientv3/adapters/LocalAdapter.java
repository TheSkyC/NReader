// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.GalleryActivity;
import com.maxwai.nclientv3.LocalActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.api.local.LocalSortType;
import com.maxwai.nclientv3.files.LocalDownloadState;
import com.maxwai.nclientv3.async.converters.CreatePdfOrZip;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.downloader.DownloadGalleryV2;
import com.maxwai.nclientv3.async.downloader.DownloadObserver;
import com.maxwai.nclientv3.async.downloader.DownloadQueue;
import com.maxwai.nclientv3.async.downloader.GalleryDownloaderV2;
import com.maxwai.nclientv3.components.classes.MultichoiceAdapter;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class LocalAdapter extends MultichoiceAdapter<Object, LocalAdapter.ViewHolder> implements Filterable {
    public enum DownloadFilter { ALL, COMPLETE, PARTIAL, DAMAGED, UNVERIFIED, ABSENT }
    private final LocalActivity context;
    private final List<LocalGallery> dataset;
    private final List<GalleryDownloaderV2> galleryDownloaders;
    private final Comparator<Object> comparatorByName = (o1, o2) -> {
        if (o1 == o2) return 0;
        boolean b1 = o1 instanceof LocalGallery;
        boolean b2 = o2 instanceof LocalGallery;
        String s1 = b1 ? ((LocalGallery) o1).getTitle() : ((GalleryDownloaderV2) o1).getPathTitle();
        String s2 = b2 ? ((LocalGallery) o2).getTitle() : ((GalleryDownloaderV2) o2).getPathTitle();
        return s1.compareTo(s2);
    };
    private final Comparator<Object> comparatorBySize = (o1, o2) -> {
        if (o1 == o2) return 0;
        long page1 = o1 instanceof LocalGallery ? Global.recursiveSize(((LocalGallery) o1).getDirectory()) : 0;
        long page2 = o2 instanceof LocalGallery ? Global.recursiveSize(((LocalGallery) o2).getDirectory()) : 0;
        return Long.compare(page1, page2);
    };
    private final Comparator<Object> comparatorByPageCount = (o1, o2) -> {
        if (o1 == o2) return 0;
        int page1 = o1 instanceof LocalGallery ? ((LocalGallery) o1).getPageCount() : 0;
        int page2 = o2 instanceof LocalGallery ? ((LocalGallery) o2).getPageCount() : 0;
        return page1 - page2;
    };
    private final Comparator<Object> comparatorByDate = (o1, o2) -> {
        if (o1 == o2) return 0;
        boolean b1 = o1 instanceof LocalGallery;
        boolean b2 = o2 instanceof LocalGallery;
        //downloading manga are newer
        long d1 = b1 ? ((LocalGallery) o1).getDirectory().lastModified() : Long.MAX_VALUE;
        long d2 = b2 ? ((LocalGallery) o2).getDirectory().lastModified() : Long.MAX_VALUE;
        if (d1 != d2) return Long.compare(d1, d2);
        return comparatorByName.compare(o1, o2);
    };
    private final Comparator<Object> comparatorByArtist = (o1, o2) -> {
        if (o1 == o2) return 0;
        boolean b1 = o1 instanceof LocalGallery;
        boolean b2 = o2 instanceof LocalGallery;
        String s1 = (b1 && ((LocalGallery) o1).getGalleryData().getTags().getCount(TagType.ARTIST) > 0)
            ? ((LocalGallery) o1).getGalleryData().getTags().getTag(TagType.ARTIST, 0).getName()
            : "";
        String s2 = (b2 && ((LocalGallery) o2).getGalleryData().getTags().getCount(TagType.ARTIST) > 0)
            ? ((LocalGallery) o2).getGalleryData().getTags().getTag(TagType.ARTIST, 0).getName()
            : "";
        if (s1.isEmpty() && !s2.isEmpty())
            return 1;
        if (!s1.isEmpty() && s2.isEmpty())
            return -1;
        return s1.compareTo(s2);
    };

    private final Comparator<Object> comparatorByGroup = (o1, o2) -> {
        if (o1 == o2) return 0;
        boolean b1 = o1 instanceof LocalGallery;
        boolean b2 = o2 instanceof LocalGallery;
        String s1 = (b1 && ((LocalGallery) o1).getGalleryData().getTags().getCount(TagType.GROUP) > 0)
            ? ((LocalGallery) o1).getGalleryData().getTags().getTag(TagType.GROUP, 0).getName()
            : "";
        String s2 = (b2 && ((LocalGallery) o2).getGalleryData().getTags().getCount(TagType.GROUP) > 0)
            ? ((LocalGallery) o2).getGalleryData().getTags().getTag(TagType.GROUP, 0).getName()
            : "";
        if (s1.isEmpty() && !s2.isEmpty())
            return 1;
        if (!s1.isEmpty() && s2.isEmpty())
            return -1;
        return s1.compareTo(s2);
    };

    private List<Object> filter;
    private DownloadFilter downloadFilter = DownloadFilter.ALL;
    @NonNull
    private String lastQuery;
    private final DownloadObserver observer = new DownloadObserver() {
        private void updatePosition(GalleryDownloaderV2 downloader) {
            context.runOnUiThread(() -> {
                int position = filter.indexOf(downloader);
                if (position >= 0) notifyItemChanged(position);
            });
        }

        @Override
        public void triggerStartDownload(GalleryDownloaderV2 downloader) {
            updatePosition(downloader);
        }

        @Override
        public void triggerUpdateProgress(GalleryDownloaderV2 downloader, int reach, int total) {
            updatePosition(downloader);
        }

        @Override
        public void triggerEndDownload(GalleryDownloaderV2 downloader) {
            LocalGallery l = downloader.localGallery();
            context.runOnUiThread(() -> {
                galleryDownloaders.remove(downloader);
                if (l != null) {
                    dataset.remove(l);
                    dataset.add(l);
                }
                sortElements();
                notifyDataSetChanged();
            });
        }

        @Override
        public void triggerCancelDownload(GalleryDownloaderV2 downloader) {
            LocalGallery retained = downloader.localGallery();
            context.runOnUiThread(() -> {
                galleryDownloaders.remove(downloader);
                if (retained != null && retained.getPageCount() > 0) {
                    dataset.remove(retained);
                    dataset.add(retained);
                }
                sortElements();
                notifyDataSetChanged();
            });
        }

        @Override
        public void triggerPauseDownload(GalleryDownloaderV2 downloader) {
            updatePosition(downloader);
        }
    };

    public LocalAdapter(LocalActivity cont, ArrayList<LocalGallery> myDataset) {
        this.context = cont;
        dataset = new CopyOnWriteArrayList<>(myDataset);
        galleryDownloaders = DownloadQueue.getDownloaders();
        lastQuery = cont.getQuery().toLowerCase(Locale.US);
        filter = new ArrayList<>(myDataset);
        filter.addAll(galleryDownloaders);

        DownloadQueue.addObserver(observer);
        sortElements();
    }

    static void startGallery(Activity context, File directory) {
        if (!directory.isDirectory()) return;
        LocalGallery ent = new LocalGallery(directory);
        ent.calculateSizes();
        new Thread(() -> {
            Intent intent = new Intent(context, GalleryActivity.class);
            intent.putExtra(context.getPackageName() + ".GALLERY", ent);
            intent.putExtra(context.getPackageName() + ".ISLOCAL", true);
            context.runOnUiThread(() -> context.startActivity(intent));
        }).start();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recycler) {
        super.onAttachedToRecyclerView(recycler);
        GenericAdapter.prepareGalleryGrid(recycler);
    }

    public void addGalleries(@Nullable List<LocalGallery> gallery) {
        if (gallery != null && !gallery.isEmpty()) {
            dataset.removeAll(gallery);
            dataset.addAll(gallery);
            sortElements();
            context.runOnUiThread(() -> notifyItemRangeChanged(0, getItemCount()));
        }
    }

    @Override
    protected ViewGroup getMaster(ViewHolder holder) {
        return holder.layout;
    }

    @Override
    protected Object getItemAt(int position) {
        return filter.get(position);
    }

    private CopyOnWriteArrayList<Object> createHash(List<GalleryDownloaderV2> galleryDownloaders, List<LocalGallery> dataset) {
        HashMap<String, Object> hashMap = new HashMap<>(dataset.size() + galleryDownloaders.size());
        for (LocalGallery gall : dataset) {
            if (gall != null && matchesQuery(gall))
                hashMap.put(gall.getTrueTitle(), gall);
        }

        for (GalleryDownloaderV2 gall : galleryDownloaders) {
            if (gall != null && gall.getPathTitle().toLowerCase(Locale.US).contains(lastQuery))
                hashMap.put(gall.getTruePathTitle(), gall);
        }

        ArrayList<Object> arr = new ArrayList<>(hashMap.values());

        sortItems(arr);

        return new CopyOnWriteArrayList<>(arr);
    }

    private boolean matchesQuery(LocalGallery gallery) {
        if (!matchesDownloadFilter(gallery)) return false;
        if (lastQuery.isEmpty()) return true;
        if (gallery.getTitle().toLowerCase(Locale.US).contains(lastQuery)) return true;
        for (Tag tag : gallery.getGalleryData().getTags().getAllTagsList())
            if (tag.getName().toLowerCase(Locale.US).contains(lastQuery)) return true;
        return false;
    }

    public void setDownloadFilter(DownloadFilter value) {
        downloadFilter = value == null ? DownloadFilter.ALL : value;
        getFilter().filter(lastQuery);
    }

    public DownloadFilter getDownloadFilter() { return downloadFilter; }

    private boolean matchesDownloadFilter(LocalGallery gallery) {
        if (downloadFilter == DownloadFilter.ALL) return true;
        LocalDownloadState state = LocalDownloadState.inspect(gallery.getDirectory());
        return state.name().equals(downloadFilter.name());
    }

    private void sortItems(ArrayList<Object> arr) {
        LocalSortType type = Global.getLocalSortType();
        if (type.type == LocalSortType.Type.RANDOM) {
            Collections.shuffle(arr, Utility.RANDOM);
        } else {
            try {
                arr.sort(getComparator(type.type));
            } catch (IllegalArgumentException ignore) {
            }
            if (type.descending) Collections.reverse(arr);
        }
    }

    private Comparator<Object> getComparator(LocalSortType.Type type) {
        switch (type) {
            case DATE:
                return comparatorByDate;
            case TITLE:
                return comparatorByName;
            case PAGE_COUNT:
                return comparatorByPageCount;
            case ARTIST:
                return comparatorByArtist;
            case GROUP:
                return comparatorByGroup;
            //case SIZE:return comparatorBySize;
        }
        return comparatorByName;
    }

    private void sortElements() {
        filter = createHash(galleryDownloaders, dataset);
    }

    @NonNull
    @Override
    protected ViewHolder onCreateMultichoiceViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_layout, parent, false));
    }

    @Override
    public int getItemViewType(int position) {
        return filter.get(position) instanceof LocalGallery ? 0 : 1;
    }

    private void bindGallery(@NonNull final ViewHolder holder, LocalGallery ent) {
        if (holder.flag != null) holder.flag.setVisibility(View.GONE);
        ImageDownloadUtility.loadImage(context, ent.getPage(ent.getMin()), holder.imgView);
        holder.title.setText(ent.getTitle());
        holder.pages.setText(String.format(Locale.US, "%d", ent.getPageCount()));

        holder.title.setOnClickListener(v -> {
            Layout layout = holder.title.getLayout();
            if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0)
                holder.title.setMaxLines(7);
            else if (holder.title.getMaxLines() == 7) holder.title.setMaxLines(3);
            else holder.layout.performClick();
        });

        /*holder.layout.setOnLongClickListener(v -> {
            createContextualMenu(position);
            return true;
        });*/
        holder.stateBadge.bind(ent);
    }

    public void updateColor(int id) {
        if (id < 0) return;
        for (int i = 0; i < filter.size(); i++) {
            Object o = filter.get(i);
            if (!(o instanceof LocalGallery)) continue;
            LocalGallery lg = (LocalGallery) o;
            if (lg.getId() == id) notifyItemChanged(i);
        }
    }

    @Override
    protected void defaultMasterAction(int position) {
        if (position < 0 || filter.size() <= position) return;
        if (filter.get(position) instanceof GalleryDownloaderV2) {
            GalleryCardActions.task(context, (GalleryDownloaderV2) filter.get(position));
            return;
        }
        if (!(filter.get(position) instanceof LocalGallery)) return;
        LocalGallery lg = (LocalGallery) filter.get(position);
        startGallery(context, lg.getDirectory());
        context.setIdGalleryPosition(lg.getId());
    }

    private void bindDownload(@NonNull final ViewHolder holder, int position, GalleryDownloaderV2 downloader) {
        if (holder.stateBadge != null) {
            ImageDownloadUtility.loadImage(context, downloader.getThumbnail(), holder.imgView);
            holder.title.setText(downloader.getTitle());
            if (holder.flag != null) holder.flag.setVisibility(View.GONE);
            holder.stateBadge.bindDownload(downloader);
            return;
        }
        int percentage = downloader.getPercentage();
        //if (!downloader.hasData())return;
        ImageDownloadUtility.loadImage(context, downloader.getThumbnail(), holder.imgView);
        holder.title.setText(downloader.getPathTitle());
        holder.cancelButton.setOnClickListener(v -> removeDownloader(downloader));
        switch (downloader.getStatus()) {
            case FAILED:
                holder.playButton.setImageResource(R.drawable.ic_play);
                holder.playButton.setOnClickListener(v -> showFailure(downloader));
                break;
            case PAUSED:
                holder.playButton.setImageResource(R.drawable.ic_play);
                holder.playButton.setOnClickListener(v -> {
                    downloader.retry();
                    DownloadGalleryV2.startWork(context);
                    notifyItemChanged(position);
                });
                break;
            case DOWNLOADING:
                holder.playButton.setImageResource(R.drawable.ic_pause);
                holder.playButton.setOnClickListener(v -> {
                    downloader.setStatus(GalleryDownloaderV2.Status.PAUSED);
                    notifyItemChanged(position);
                });
                break;
            case NOT_STARTED:
                holder.playButton.setImageResource(R.drawable.ic_play);
                holder.playButton.setOnClickListener(v -> DownloadQueue.givePriority(downloader));
                break;
        }
        holder.progress.setText(context.getString(R.string.percentage_format, percentage));
        if (downloader.getStatus() == GalleryDownloaderV2.Status.FAILED) {
            holder.progress.setText(R.string.download_failed);
            holder.progress.setContentDescription(context.getString(failureMessage(downloader)));
            holder.progress.setOnClickListener(view -> showFailure(downloader));
        } else {
            holder.progress.setContentDescription(null);
            holder.progress.setOnClickListener(null);
        }
        holder.playButton.setContentDescription(context.getString(downloader.getStatus() == GalleryDownloaderV2.Status.DOWNLOADING
            ? R.string.pause_download : R.string.start_download));
        holder.progress.setVisibility(downloader.getStatus() == GalleryDownloaderV2.Status.NOT_STARTED ? View.GONE : View.VISIBLE);
        holder.progressBar.setProgress(percentage);
        holder.progressBar.setIndeterminate(downloader.getStatus() == GalleryDownloaderV2.Status.NOT_STARTED);
        Global.setTint(context, holder.playButton.getDrawable());
        Global.setTint(context, holder.cancelButton.getDrawable());
    }

    private void removeDownloader(GalleryDownloaderV2 downloader) {
        GalleryCardActions.task(context, downloader);
    }

    private int failureMessage(GalleryDownloaderV2 downloader) {
        switch (downloader.getFailure()) {
            case AUTHENTICATION: return R.string.download_error_authentication;
            case RATE_LIMIT: return R.string.download_error_rate_limit;
            case STORAGE: return R.string.download_error_storage;
            case NOT_FOUND: return R.string.download_error_not_found;
            case INVALID_DATA: return R.string.download_error_invalid_data;
            default: return R.string.download_error_network;
        }
    }

    private void showFailure(GalleryDownloaderV2 downloader) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_failed)
            .setMessage(failureMessage(downloader))
            .setPositiveButton(R.string.retry, (dialog, which) -> {
                downloader.retry();
                DownloadGalleryV2.startWork(context);
                notifyDataSetChanged();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    @Override
    public long getItemId(int position) {
        if (position == -1) return -1;
        return Objects.hash(filter.get(position).hashCode(), position);
    }

    @Override
    public void onBindMultichoiceViewHolder(@NonNull ViewHolder holder, int position) {
        if (filter.get(position) instanceof LocalGallery)
            bindGallery(holder, (LocalGallery) filter.get(position));
        else
            bindDownload(holder, position, (GalleryDownloaderV2) filter.get(position));
    }

    private void showDialogDelete() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.delete_galleries).setMessage(getAllGalleries());
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            ArrayList<Object> coll = new ArrayList<>(getSelected());
            for (Object o : coll) {
                filter.remove(o);
                if (o instanceof LocalGallery) {
                    dataset.remove(o);
                    Global.recursiveDelete(((LocalGallery) o).getDirectory());
                } else if (o instanceof GalleryDownloaderV2) {
                    DownloadQueue.remove((GalleryDownloaderV2) o, true);
                }
            }
            com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
            context.runOnUiThread(this::notifyDataSetChanged);
        }).setNegativeButton(R.string.no, null).setCancelable(true);
        builder.show();
    }

    private String getAllGalleries() {
        StringBuilder builder = new StringBuilder();
        for (Object o : getSelected()) {
            if (o instanceof LocalGallery) builder.append(((LocalGallery) o).getTitle());
            else builder.append(((GalleryDownloaderV2) o).getTitle());
            builder.append('\n');
        }
        return builder.toString();
    }

    private void showDialogPDF() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.create_pdf).setMessage(getAllGalleries());
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            for (Object o : getSelected()) {
                if (!(o instanceof LocalGallery)) continue;
                CreatePdfOrZip.startWork(context, (LocalGallery) o, true);
            }
        }).setNegativeButton(R.string.no, null).setCancelable(true);
        builder.show();
    }


    private void showDialogZip() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.create_zip).setMessage(getAllGalleries());
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            for (Object o : getSelected()) {
                if (!(o instanceof LocalGallery)) continue;
                CreatePdfOrZip.startWork(context, (LocalGallery) o, false);
            }
        }).setNegativeButton(R.string.no, null).setCancelable(true);
        builder.show();

    }

    public boolean hasSelectedClass(Class<?> c) {
        for (Object x : getSelected()) if (x.getClass() == c) return true;
        return false;
    }

    @Override
    public int getItemCount() {
        return filter.size();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String query = constraint.toString().toLowerCase(Locale.US);
                if (lastQuery.equals(query)) return null;
                FilterResults results = new FilterResults();
                lastQuery = query;
                results.values = createHash(galleryDownloaders, dataset);
                return results;
            }

            @SuppressLint("NotifyDataSetChanged")
            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                if (results != null && results.values instanceof CopyOnWriteArrayList) {
                    filter = (CopyOnWriteArrayList<Object>) results.values;
                    context.runOnUiThread(() -> notifyDataSetChanged());
                }
            }
        };
    }

    public void removeObserver() {
        DownloadQueue.removeObserver(observer);
    }

    public void viewRandom() {
        if (dataset.isEmpty()) return;
        int x = Utility.RANDOM.nextInt(dataset.size());
        startGallery(context, dataset.get(x).getDirectory());
    }

    public void sortChanged() {
        sortElements();
        context.runOnUiThread(() -> notifyItemRangeChanged(0, getItemCount()));
    }

    public void startSelected() {
        for (Object o : getSelected()) {
            if (!(o instanceof GalleryDownloaderV2)) continue;
            GalleryDownloaderV2 d = (GalleryDownloaderV2) o;
            if (d.getStatus() == GalleryDownloaderV2.Status.PAUSED || d.getStatus() == GalleryDownloaderV2.Status.FAILED)
                d.retry();
            DownloadGalleryV2.startWork(context);
        }
        context.runOnUiThread(this::notifyDataSetChanged);
    }

    public void pauseSelected() {
        for (Object o : getSelected()) {
            if (!(o instanceof GalleryDownloaderV2)) continue;
            GalleryDownloaderV2 d = (GalleryDownloaderV2) o;
            d.setStatus(GalleryDownloaderV2.Status.PAUSED);
        }
        context.runOnUiThread(this::notifyDataSetChanged);
    }

    public void deleteSelected() {
        showDialogDelete();
    }

    public void zipSelected() {
        showDialogZip();
    }

    public void pdfSelected() {
        showDialogPDF();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgView;
        final View overlay;
        final TextView title, pages, flag, progress;
        final ViewGroup layout;
        final ImageButton playButton, cancelButton;
        final ProgressBar progressBar;
        final GalleryStateBadge stateBadge;

        ViewHolder(View v) {
            super(v);
            //Both
            imgView = v.findViewById(R.id.image);
            title = v.findViewById(R.id.title);
            //Local
            pages = v.findViewById(R.id.pages);
            layout = v.findViewById(R.id.master_layout);
            flag = v.findViewById(R.id.flag);
            overlay = v.findViewById(R.id.overlay);
            //Downloader
            progress = itemView.findViewById(R.id.progress);
            progressBar = itemView.findViewById(R.id.progressBar);
            playButton = itemView.findViewById(R.id.playButton);
            cancelButton = itemView.findViewById(R.id.cancelButton);
            stateBadge = v.findViewById(R.id.gallery_state) == null ? null : new GalleryStateBadge(v);
        }
    }
}
