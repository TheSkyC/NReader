// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.Intent;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.CopyToClipboardActivity;
import com.maxwai.nclientv3.GalleryActivity;
import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.ZoomActivity;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GalleryData;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.components.TagList;
import com.maxwai.nclientv3.api.enums.SpecialTagIds;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.widgets.CustomGridLayoutManager;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
    private static final int[] TAG_NAMES = {
        R.string.unknown,
        R.string.tag_parody_gallery,
        R.string.tag_character_gallery,
        R.string.tag_tag_gallery,
        R.string.tag_artist_gallery,
        R.string.tag_group_gallery,
        R.string.tag_language_gallery,
        R.string.tag_category_gallery,
    };
    private final SparseIntArray angles = new SparseIntArray();
    private final GalleryActivity context;
    private final GenericGallery gallery;
    private GalleryFolder directory = null;
    private Policy policy;
    private int colCount;
    private final boolean details;
    private boolean loading;
    private final boolean[] expandedTags = new boolean[TagType.values.length];

    public GalleryAdapter(GalleryActivity cont, GenericGallery gallery, int colCount) {
        this(cont, gallery, colCount, false);
    }

    public GalleryAdapter(GalleryActivity cont, GenericGallery gallery, int colCount, boolean details) {
        this.context = cont;
        this.gallery = gallery;
        this.details = details;
        this.loading = gallery instanceof SimpleGallery;
        setColCount(colCount);
        try {
            if (gallery instanceof LocalGallery) {
                directory = gallery.getGalleryFolder();
            } else if (Global.hasStoragePermission(cont)) {
                if (gallery.getId() != -1) {
                    File f = Global.findGalleryFolder(context, gallery.getId());
                    if (f != null) directory = new GalleryFolder(f);
                } else {
                    directory = new GalleryFolder(gallery.getTitle());
                }
            }
        } catch (IllegalArgumentException ignore) {
            directory = null;
        }
        LogUtility.d("Max maxSize: " + gallery.getMaxSize() + ", min maxSize: " + gallery.getMinSize());
    }

    public Type positionToType(int pos) {
        if (details) return pos == 0 ? Type.HEADER : pos == 1 ? Type.TAG : Type.RELATED;
        if (pos == 0) return Type.TAG;
        if (pos > gallery.getPageCount()) return Type.RELATED;
        return Type.PAGE;
    }

    public void setColCount(int colCount) {
        this.colCount = colCount;
        applyProportionPolicy();
    }

    private void applyProportionPolicy() {
        if (colCount == 1) policy = Policy.FULL;
        else policy = Policy.PROPORTION;
        LogUtility.d("NEW POLICY: " + policy);
    }

    public GalleryFolder getDirectory() {
        return directory;
    }

    @NonNull
    @Override
    public GalleryAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int id = 0;
        switch (Type.values()[viewType]) {
            case HEADER:
                id = R.layout.gallery_detail_header;
                break;
            case TAG:
                id = R.layout.tags_layout;
                break;
            case PAGE:
                switch (policy) {
                    case FULL:
                        id = R.layout.image_void_full;
                        break;
                    case PROPORTION:
                        id = R.layout.image_void_static;
                        break;
                }
                break;
            case RELATED:
                id = R.layout.related_recycler;
                break;
        }
        return new GalleryAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(id, parent, false), Type.values()[viewType]);
    }

    @Override
    public void onBindViewHolder(@NonNull final GalleryAdapter.ViewHolder holder, int position) {

        switch (positionToType(holder.getBindingAdapterPosition())) {
            case HEADER:
                loadHeader(holder);
                break;
            case TAG:
                if (!details) {
                    holder.itemView.setVisibility(View.GONE);
                    holder.itemView.getLayoutParams().height = 0;
                    break;
                }
                loadTagLayout(holder);
                break;
            case PAGE:
                loadPageLayout(holder);
                break;
            case RELATED:
                loadRelatedLayout(holder);
                break;
        }
    }

    private void loadHeader(ViewHolder holder) {
        TextView title = holder.master.findViewById(R.id.gallery_title);
        title.setText(gallery.getTitle());
        ImageView cover = holder.master.findViewById(R.id.gallery_cover);
        View loading = holder.master.findViewById(R.id.gallery_loading);
        File firstPage = directory == null ? null : directory.getPage(1);
        if (firstPage != null && firstPage.exists()) {
            ImageDownloadUtility.loadImageOp(context, cover, firstPage, 0);
        } else if (gallery instanceof Gallery && gallery.hasGalleryData()) {
            ImageDownloadUtility.loadImage(context, ((Gallery) gallery).getCover(), cover);
        } else if (gallery instanceof SimpleGallery) {
            ImageDownloadUtility.loadImage(context, ((SimpleGallery) gallery).getThumbnail(), cover);
        } else {
            ImageDownloadUtility.loadImage(R.drawable.ic_logo, cover);
        }
        if (loading != null) loading.setVisibility(this.loading ? View.VISIBLE : View.GONE);
        context.bindReadingActions(holder.master);
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        if (details) notifyItemChanged(0);
    }

    private void loadRelatedLayout(ViewHolder holder) {
        LogUtility.d("Called RElated");
        final RecyclerView recyclerView = holder.master.findViewById(R.id.recycler);
        if (gallery.isLocal() || !(this.gallery instanceof Gallery)) {
            holder.master.setVisibility(View.GONE);
            return;
        }
        final Gallery gallery = (Gallery) this.gallery;
        if (!gallery.isRelatedLoaded() || gallery.getRelated().isEmpty()) {
            holder.master.setVisibility(View.GONE);
            return;
        } else holder.master.setVisibility(View.VISIBLE);
        recyclerView.setLayoutManager(new CustomGridLayoutManager(context, 1, RecyclerView.HORIZONTAL, false));
        if (gallery.isRelatedLoaded()) {
            ListAdapter adapter = new ListAdapter(context);
            adapter.addGalleries(new ArrayList<>(gallery.getRelated()));
            recyclerView.setAdapter(adapter);
        }
    }

    private void loadTagLayout(ViewHolder holder) {
        final ViewGroup vg = holder.master.findViewById(R.id.tag_master);
        final TextView idContainer = holder.master.findViewById(R.id.id_num);
        initializeIdContainer(idContainer);
        holder.master.findViewById(R.id.gallery_metadata).setVisibility(hasTags() ? View.VISIBLE : View.GONE);
        if (!hasTags()) {
            ViewGroup.LayoutParams layoutParams = vg.getLayoutParams();
            layoutParams.height = 0;
            vg.setLayoutParams(layoutParams);
            return;
        }
        final LayoutInflater inflater = context.getLayoutInflater();

        int tagCount, idStringTagName;
        ViewGroup lay;
        ChipGroup cg;
        TagList tagList = this.gallery.getGalleryData().getTags();
        for (TagType type : TagType.values) {
            idStringTagName = TAG_NAMES[type.getId()];
            tagCount = tagList.getCount(type);
            lay = (ViewGroup) vg.getChildAt(type.getId());
            cg = lay.findViewById(R.id.chip_group);
            lay.setVisibility(tagCount == 0 ? View.GONE : View.VISIBLE);
            ((TextView) lay.findViewById(R.id.title)).setText(idStringTagName);
            for (int a = cg.getChildCount(); a < tagCount; a++) {
                final Tag tag = tagList.getTag(type, a);
                Chip c = (Chip) inflater.inflate(R.layout.gallery_tag_chip, cg, false);
                c.setText(tag.getName());
                c.setOnClickListener(v -> {
                    Intent intent = new Intent(context, MainActivity.class);
                    intent.putExtra(context.getPackageName() + ".TAG", tag);
                    intent.putExtra(context.getPackageName() + ".ISBYTAG", true);
                    context.startActivity(intent);
                });
                c.setOnLongClickListener(v -> {
                    CopyToClipboardActivity.copyTextToClipboard(context, tag.getName());
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    return true;
                });
                cg.addView(c);
            }
            for (int index = 0; index < cg.getChildCount(); index++) {
                cg.getChildAt(index).setVisibility(expandedTags[type.getId()] || index < 8 ? View.VISIBLE : View.GONE);
            }
            TextView expand = lay.findViewById(R.id.gallery_expand_category);
            expand.setVisibility(tagCount > 8 ? View.VISIBLE : View.GONE);
            expand.setText(expandedTags[type.getId()] ? R.string.gallery_collapse_tags : R.string.gallery_expand_tags);
            expand.setOnClickListener(v -> {
                expandedTags[type.getId()] = !expandedTags[type.getId()];
                notifyItemChanged(1);
            });
        }
        addInfoLayout(holder, gallery.getGalleryData());
    }

    private void initializeIdContainer(TextView idContainer) {
        if (gallery.getId() <= 0) {
            idContainer.setVisibility(View.GONE);
            return;
        }
        String id = Integer.toString(gallery.getId());
        idContainer.setText(id);
        idContainer.setVisibility(gallery.getId() != SpecialTagIds.INVALID_ID ? View.VISIBLE : View.GONE);
        idContainer.setOnClickListener(v -> {
            CopyToClipboardActivity.copyTextToClipboard(context, id);
            context.runOnUiThread(() ->
                Toast.makeText(context, R.string.id_copied_to_clipboard, Toast.LENGTH_SHORT).show()
            );
        });
    }

    private void addInfoLayout(ViewHolder holder, GalleryData gallery) {
        TextView text = holder.master.findViewById(R.id.page_stat);
        text.setText(context.getString(R.string.page_count_format, gallery.getPageCount()));
        text = holder.master.findViewById(R.id.upload_stat);
        String date = android.text.format.DateFormat.getDateFormat(context).format(gallery.getUploadDate());
        text.setText(date);
        text.setContentDescription(context.getString(R.string.upload_date_format, date,
            android.text.format.DateFormat.getTimeFormat(context).format(gallery.getUploadDate())));
        text = holder.master.findViewById(R.id.favorite_stat);
        text.setText(context.getString(R.string.favorite_count_format, gallery.getFavoriteCount()));

    }

    private void loadPageLayout(ViewHolder holder) {
        final int pos = holder.getBindingAdapterPosition();
        final ImageView imgView = holder.master.findViewById(R.id.image);

        imgView.setOnClickListener(v -> startGallery(holder.getBindingAdapterPosition()));
        imgView.setOnLongClickListener(null);
        holder.master.setOnClickListener(v -> startGallery(holder.getBindingAdapterPosition()));
        holder.master.setOnLongClickListener(null);

        holder.pageNumber.setText(String.format(Locale.US, "%d", pos));

        if (policy == Policy.FULL) {
            PhotoView photoView = (PhotoView) imgView;
            photoView.setZoomable(Global.isZoomOneColumn());
            photoView.setOnMatrixChangeListener(rect -> photoView.setAllowParentInterceptOnEdge(photoView.getScale() <= 1f));
            photoView.setOnClickListener(v -> {
                if (photoView.getScale() <= 1f)
                    startGallery(holder.getBindingAdapterPosition());
            });
            View.OnLongClickListener listener = v -> {
                optionDialog(imgView, pos);
                return true;
            };
            imgView.setOnLongClickListener(listener);
            holder.master.setOnLongClickListener(listener);
        }

        loadImageOnPolicy(imgView, pos);


    }

    private void optionDialog(ImageView imgView, final int pos) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.select_dialog_item);
        adapter.add(context.getString(R.string.share));
        adapter.add(context.getString(R.string.rotate_image));
        adapter.add(context.getString(R.string.bookmark_here));
        if (Global.hasStoragePermission(context))
            adapter.add(context.getString(R.string.save_page));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.settings).setIcon(R.drawable.ic_share);
        builder.setAdapter(adapter, (dialog, which) -> {
            switch (which) {
                case 0:
                    openSendImageDialog(imgView, pos);
                    break;
                case 1:
                    rotate(pos);
                    break;
                case 2:
                    Queries.ResumeTable.insert(gallery.getId(), pos);
                    break;
                case 3:
                    String name = String.format(Locale.US, "%d-%d.jpg", gallery.getId(), pos);
                    Utility.saveImage(imgView.getDrawable(), new File(Global.SCREENFOLDER, name));
                    break;
            }
        }).show();
    }

    private void openSendImageDialog(ImageView img, int pos) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> sendImage(img, pos, true))
            .setNegativeButton(R.string.no, (dialog, which) -> sendImage(img, pos, false))
            .setCancelable(true).setTitle(R.string.send_with_title)
            .setMessage(R.string.caption_send_with_title)
            .show();
    }

    private void sendImage(ImageView img, int pos, boolean text) {
        Utility.sendImage(context, img.getDrawable(), text ? gallery.sharePageUrl(pos - 1) : null);
    }

    private void rotate(int pos) {
        angles.append(pos, (angles.get(pos) + 270) % 360);
        context.runOnUiThread(() -> notifyItemChanged(pos));
    }

    private void startGallery(int page) {
        if (!gallery.isLocal() && Global.getDownloadPolicy() == Global.DataUsageType.NONE
            && (directory == null || directory.getPage(page) == null)) {
            context.runOnUiThread(() ->
                Toast.makeText(context, R.string.enable_network_to_continue, Toast.LENGTH_SHORT).show()
            );
            return;
        }
        Intent intent = new Intent(context, ZoomActivity.class);
        intent.putExtra(context.getPackageName() + ".GALLERY", gallery);
        intent.putExtra(context.getPackageName() + ".DIRECTORY", directory);
        intent.putExtra(context.getPackageName() + ".PAGE", page);
        context.startActivity(intent);
    }

    private void loadImageOnPolicy(ImageView imgView, int pos) {
        final File file = directory == null ? null : directory.getPage(pos);
        int angle = angles.get(pos);

        if (policy == Policy.FULL) {
            if (file != null && file.exists())
                ImageDownloadUtility.loadImageOp(context, imgView, file, angle);
            else if (!gallery.isLocal()) {
                Gallery ent = (Gallery) gallery;
                ImageDownloadUtility.loadImageOp(context, imgView, ent, pos - 1, angle);
            } else ImageDownloadUtility.loadImage(R.drawable.ic_logo, imgView);
        } else {
            if (file != null && file.exists())
                ImageDownloadUtility.loadImage(context, file, imgView);
            else if (!gallery.isLocal()) {
                Gallery ent = (Gallery) gallery;
                ImageDownloadUtility.downloadPage(context, imgView, ent, pos - 1, false);
            } else ImageDownloadUtility.loadImage(R.drawable.ic_logo, imgView);
        }
    }


    private boolean hasTags() {
        return gallery.hasGalleryData();
    }

    @Override
    public int getItemViewType(int position) {
        return positionToType(position).ordinal();
    }

    @Override
    public int getItemCount() {
        return details ? 3 : gallery.getPageCount() + 1;
    }

    public enum Type {TAG, PAGE, RELATED, HEADER}

    public enum Policy {PROPORTION, FULL}

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View master;
        final TextView pageNumber;

        ViewHolder(View v, Type type) {
            super(v);
            master = v.findViewById(R.id.master);
            pageNumber = v.findViewById(R.id.page_number);
            if (Global.useRtl()) v.setRotationY(180);
            if (type == Type.RELATED) Global.applyFastScroller(master.findViewById(R.id.recycler));
        }
    }

}
