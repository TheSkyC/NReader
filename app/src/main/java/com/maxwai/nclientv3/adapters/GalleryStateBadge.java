// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.maxwai.nclientv3.ApiKeyActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.OnlineFavoriteClient;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.components.TagList;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.GalleryState;
import com.maxwai.nclientv3.async.database.GalleryStateRepository;
import com.maxwai.nclientv3.async.downloader.DownloadObserver;
import com.maxwai.nclientv3.async.downloader.DownloadQueue;
import com.maxwai.nclientv3.async.downloader.GalleryDownloaderV2;
import com.maxwai.nclientv3.settings.AuthStore;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class GalleryStateBadge implements View.OnAttachStateChangeListener {
    private final View item;
    private final View strip;
    private final ImageButton favorite;
    private final ImageView onlineFavorite;
    private final ImageButton localFiles;
    private final TextView customStatus;
    private final TextView progress;
    private final TextView author;
    private final CircularProgressIndicator downloadProgress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private GenericGallery gallery;
    private GalleryDownloaderV2 task;
    private GalleryState state;
    private int id;
    private int onlineTotal;
    private File directory;
    private long binding;
    private long request;
    private boolean favoritePending;
    private boolean onlineFavoritePending;
    private Boolean onlineFavoriteState;
    private String onlineFavoriteIdentity;
    private boolean compactProgressStyle;
    private final DownloadObserver observer = new DownloadObserver() {
        private void update(GalleryDownloaderV2 downloader) {
            main.post(() -> {
                if (task == downloader && strip.isAttachedToWindow() && state != null) render(state);
            });
        }

        @Override public void triggerStartDownload(GalleryDownloaderV2 downloader) { update(downloader); }
        @Override public void triggerUpdateProgress(GalleryDownloaderV2 downloader, int reach, int total) { update(downloader); }
        @Override public void triggerEndDownload(GalleryDownloaderV2 downloader) { refreshAfter(downloader); }
        @Override public void triggerCancelDownload(GalleryDownloaderV2 downloader) { refreshAfter(downloader); }
        @Override public void triggerPauseDownload(GalleryDownloaderV2 downloader) { update(downloader); }

        private void refreshAfter(GalleryDownloaderV2 downloader) {
            main.post(() -> {
                if (task == downloader && strip.isAttachedToWindow()) refresh();
            });
        }
    };

    GalleryStateBadge(View item) {
        this.item = item;
        strip = item.findViewById(R.id.gallery_state);
        favorite = item.findViewById(R.id.gallery_favorite);
        onlineFavorite = item.findViewById(R.id.gallery_online_favorite);
        localFiles = item.findViewById(R.id.gallery_local_files);
        customStatus = item.findViewById(R.id.gallery_custom_status);
        progress = item.findViewById(R.id.gallery_reading_progress);
        item.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            updateProgressStyle());
        updateProgressStyle();
        author = item.findViewById(R.id.gallery_author);
        author.setCompoundDrawablePadding(Math.round(4 * item.getResources().getDisplayMetrics().density));
        TextViewCompat.setCompoundDrawableTintList(author, author.getTextColors());
        downloadProgress = item.findViewById(R.id.gallery_download_progress);
        item.addOnAttachStateChangeListener(this);
        ViewCompat.addAccessibilityAction(favorite,
            item.getContext().getString(R.string.favorite_action_local_accessibility),
            (view, arguments) -> {
                toggleLocalFavorite();
                return true;
            });
        ViewCompat.addAccessibilityAction(favorite,
            item.getContext().getString(R.string.favorite_action_online_accessibility),
            (view, arguments) -> {
                toggleOnlineFavorite();
                return true;
            });
        favorite.setOnLongClickListener(view -> {
            performSecondaryFavoriteAction();
            return true;
        });
        favorite.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    performSecondaryFavoriteAction();
                return true;
            }
            return false;
        });
    }

    private void updateProgressStyle() {
        float density = item.getResources().getDisplayMetrics().density;
        boolean compact = item.getWidth() > 0 && item.getWidth() / density <= 140f;
        if (compact == compactProgressStyle) return;
        compactProgressStyle = compact;
        int horizontal = Math.round((compact ? 4 : 6) * density);
        int vertical = Math.round((compact ? 2 : 3) * density);
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 10 : 11);
        progress.setPaddingRelative(horizontal, vertical, horizontal, vertical);
    }

    void bind(GenericGallery gallery) {
        reset();
        this.gallery = gallery;
        id = gallery.getId();
        directory = gallery instanceof LocalGallery ? ((LocalGallery) gallery).getDirectory() : null;
        onlineTotal = directory == null ? gallery.getPageCount() : 0;
        bindAuthor(gallery);
        refresh();
    }

    void bindDownload(GalleryDownloaderV2 downloader) {
        reset();
        gallery = downloader.getGallery();
        id = downloader.getId();
        directory = downloader.getFolder();
        onlineTotal = gallery == null ? 0 : gallery.getPageCount();
        bindAuthor(gallery);
        follow(downloader);
        refresh();
    }

    private void reset() {
        binding++;
        request++;
        state = null;
        gallery = null;
        favoritePending = false;
        onlineFavoritePending = false;
        onlineFavoriteState = null;
        onlineFavoriteIdentity = null;
        follow(null);
        strip.setVisibility(View.INVISIBLE);
        customStatus.setVisibility(View.GONE);
        progress.setVisibility(View.INVISIBLE);
        downloadProgress.setVisibility(View.GONE);
        favorite.setOnClickListener(null);
        localFiles.setOnClickListener(null);
        favorite.setEnabled(false);
        onlineFavorite.setVisibility(View.GONE);
        localFiles.setEnabled(false);
        item.findViewById(R.id.pages).setVisibility(View.GONE);
        TextView title = item.findViewById(R.id.title);
        title.setMaxLines(2);
        title.setTooltipText(title.getText());
        title.setOnClickListener(view -> item.findViewById(R.id.master_layout).performClick());
    }

    private void bindAuthor(GenericGallery gallery) {
        TagList tags = gallery instanceof SimpleGallery ? ((SimpleGallery) gallery).getTags()
            : gallery != null && gallery.hasGalleryData() ? gallery.getGalleryData().getTags() : null;
        String names = creditNames(tags, TagType.ARTIST);
        boolean group = names.isEmpty();
        if (group) names = creditNames(tags, TagType.GROUP);
        boolean visible = !names.isEmpty();
        author.setText(names);
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(author,
            visible && group ? R.drawable.ic_credit_group : 0, 0, 0, 0);
        CharSequence description = !visible ? null : group
            ? author.getContext().getString(R.string.cover_group_credit, names) : names;
        author.setContentDescription(description);
        author.setTooltipText(description);
        // Keep the language flag and neighboring cards aligned when credits are absent.
        author.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        author.setAlpha(item.findViewById(R.id.title).getAlpha());
    }

    private static String creditNames(TagList tags, TagType type) {
        Set<String> names = new LinkedHashSet<>();
        if (tags != null) {
            for (Tag tag : tags.retrieveForType(type)) {
                if (tag == null || tag.getName() == null) continue;
                String name = tag.getName().trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return TextUtils.join(", ", names);
    }

    private void refresh() {
        if (id <= 0 && gallery == null) return;
        long requestedBinding = binding;
        long requestedVersion = ++request;
        GalleryStateRepository.load(strip.getContext(), id, directory, resolved -> {
            if (binding != requestedBinding || request != requestedVersion) return;
            state = resolved;
            GalleryDownloaderV2 found = null;
            for (GalleryDownloaderV2 downloader : DownloadQueue.getDownloaders()) {
                if (downloader.getId() == id && downloader.getStatus() != GalleryDownloaderV2.Status.FINISHED) {
                    found = downloader;
                    break;
                }
            }
            follow(found);
            render(resolved);
        });
    }

    private void follow(GalleryDownloaderV2 downloader) {
        if (task == downloader) return;
        if (task != null) task.removeObserver(observer);
        task = downloader;
        if (task != null && strip.isAttachedToWindow()) task.addObserver(observer);
    }

    private void render(GalleryState state) {
        Context context = strip.getContext();
        long renderedBinding = binding;
        synchronizeOnlineFavoriteIdentity(context);
        boolean onlinePrimary = onlinePrimaryPreference() && onlineFavoriteAvailable();
        favorite.setVisibility(View.VISIBLE);
        boolean onlineSelected = Boolean.TRUE.equals(onlineFavoriteState);
        favorite.setSelected(onlinePrimary ? onlineSelected : state.favorite);
        favorite.setImageResource(onlinePrimary
            ? (onlineSelected ? R.drawable.ic_star : R.drawable.ic_star_border)
            : (state.favorite ? R.drawable.ic_cover_heart_fill : R.drawable.ic_cover_heart));
        tint(favorite, onlinePrimary ? (onlineSelected ? R.color.cover_favorite
            : onlineFavoriteState == null ? R.color.cover_unknown : android.R.color.white)
            : (state.favorite ? R.color.cover_favorite : android.R.color.white));
        favorite.setEnabled(id > 0 && gallery != null && !favoritePending && !onlineFavoritePending);
        favorite.setAlpha(id > 0 && gallery != null ? 1f : .5f);
        String favoriteLabel = context.getString(state.favorite ? R.string.cover_remove_favorite : R.string.cover_add_favorite);
        String primaryLabel = onlinePrimary
            ? context.getString(Boolean.TRUE.equals(onlineFavoriteState)
                ? R.string.cover_online_favorite_remove
                : onlineFavoriteState == null ? R.string.cover_online_favorite_unknown
                    : R.string.cover_online_favorite_add)
            : favoriteLabel;
        favorite.setContentDescription(primaryLabel);
        favorite.setTooltipText(primaryLabel);
        favorite.setOnClickListener(view -> performPrimaryFavoriteAction());
        renderOnlineFavorite(context, state.favorite, onlinePrimary);

        customStatus.setVisibility(state.statusName == null ? View.GONE : View.VISIBLE);
        if (state.statusName != null) {
            String label = context.getString(R.string.state_custom_status, state.statusName);
            customStatus.setText(state.statusName);
            customStatus.setContentDescription(label);
            customStatus.setTooltipText(label);
            int color = ColorUtils.setAlphaComponent(state.statusColor, 255);
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(4 * context.getResources().getDisplayMetrics().density);
            customStatus.setBackground(background);
            customStatus.setTextColor(ColorUtils.calculateContrast(Color.WHITE, color) >= 4.5 ? Color.WHITE : Color.BLACK);
        }

        int total = onlineTotal > 0 ? onlineTotal : state.expectedPages;
        if (state.position != null) {
            progress.setText(total > 0
                ? context.getString(R.string.state_reading_fraction, state.position.page, total)
                : context.getString(R.string.state_reading_fraction_unknown, state.position.page));
            progress.setContentDescription(total > 0
                ? context.getString(R.string.state_reading_page_total, state.position.page, total)
                : context.getString(R.string.state_reading_page_unknown, state.position.page));
        } else {
            progress.setText(total > 0 ? context.getString(R.string.page_count_format, total) : "");
            progress.setContentDescription(progress.getText());
        }
        progress.setVisibility(progress.length() == 0 ? View.GONE : View.VISIBLE);
        progress.setTooltipText(progress.getContentDescription());

        renderFiles(state, renderedBinding);
        strip.setVisibility(View.VISIBLE);
    }

    private void renderOnlineFavorite(Context context, boolean localSelected, boolean onlinePrimary) {
        boolean available = id > 0 && gallery != null && !gallery.isLocal()
            && AuthStore.hasValidApiKey(context);
        onlineFavorite.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        boolean confirmed = onlineFavoriteState != null;
        boolean selected = Boolean.TRUE.equals(onlineFavoriteState);
        onlineFavorite.setSelected(onlinePrimary ? localSelected : selected);
        onlineFavorite.setImageResource(onlinePrimary
            ? (localSelected ? R.drawable.ic_cover_heart_fill : R.drawable.ic_cover_heart)
            : (selected ? R.drawable.ic_star : R.drawable.ic_star_border));
        tint(onlineFavorite, onlinePrimary ? (localSelected ? R.color.cover_favorite : android.R.color.white)
            : (selected ? R.color.cover_favorite : confirmed ? android.R.color.white : R.color.cover_unknown));
        float offset = 8 * context.getResources().getDisplayMetrics().density;
        onlineFavorite.setTranslationX(offset);
        onlineFavorite.setTranslationY(onlinePrimary ? offset : -offset);
    }

    private void synchronizeOnlineFavoriteIdentity(Context context) {
        String identity = OnlineFavoriteClient.sessionIdentity(context);
        onlineFavoriteIdentity = identity;
        onlineFavoriteState = identity == null ? null : OnlineFavoriteClient.cachedState(context, id);
    }

    private boolean onlinePrimaryPreference() {
        Context context = item.getContext();
        String key = context.getString(R.string.preference_key_favorite_action_mode);
        try {
            return "online".equals(context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
                .getString(key, "local"));
        } catch (ClassCastException exception) {
            return false;
        }
    }

    private boolean onlineFavoriteAvailable() {
        return id > 0 && gallery != null && !gallery.isLocal()
            && AuthStore.hasValidApiKey(item.getContext());
    }

    private void performPrimaryFavoriteAction() {
        if (onlinePrimaryPreference() && onlineFavoriteAvailable()) toggleOnlineFavorite();
        else toggleLocalFavorite();
    }

    private void performSecondaryFavoriteAction() {
        if (onlinePrimaryPreference() && !onlineFavoriteAvailable()) {
            toggleOnlineFavorite();
        } else if (onlinePrimaryPreference()) {
            toggleLocalFavorite();
        } else {
            toggleOnlineFavorite();
        }
    }

    private void toggleLocalFavorite() {
        if (state == null || favoritePending || gallery == null || id <= 0) return;
        long actionBinding = binding;
        favoritePending = true;
        favorite.setEnabled(false);
        GalleryCardActions.favorite(item, gallery, state.favorite,
            () -> binding == actionBinding, () -> {
                favoritePending = false;
                refresh();
            });
    }

    private void toggleOnlineFavorite() {
        if (!onlineFavoriteAvailable()) {
            Snackbar.make(item, R.string.favorite_action_online_unavailable, Snackbar.LENGTH_LONG)
                .setAction(R.string.setting_api_key_title,
                    view -> item.getContext().startActivity(new Intent(item.getContext(), ApiKeyActivity.class)))
                .show();
            return;
        }
        if (onlineFavoritePending) return;
        synchronizeOnlineFavoriteIdentity(item.getContext());
        long actionBinding = binding;
        String actionIdentity = onlineFavoriteIdentity;
        onlineFavoritePending = true;
        favorite.setEnabled(false);
        GalleryCardActions.onlineFavorite(item, id,
            () -> binding == actionBinding,
            () -> {
                onlineFavoritePending = false;
                onlineFavoriteIdentity = OnlineFavoriteClient.sessionIdentity(item.getContext());
                onlineFavoriteState = OnlineFavoriteClient.cachedState(item.getContext(), id);
                refresh();
            }, favorited -> {
                if (Objects.equals(actionIdentity, OnlineFavoriteClient.sessionIdentity(item.getContext())))
                    onlineFavoriteState = favorited;
            });
    }

    private void renderFiles(GalleryState state, long renderedBinding) {
        Context context = strip.getContext();
        int label = R.string.download_gallery;
        int icon = R.drawable.ic_cover_download;
        int color = android.R.color.white;
        boolean repair = false;
        switch (state.local) {
            case COMPLETE:
                label = R.string.state_download_complete;
                icon = R.drawable.ic_cover_check_circle;
                color = R.color.cover_complete;
                break;
            case PARTIAL:
                label = R.string.state_download_partial;
                icon = R.drawable.ic_cover_exclamation_circle;
                color = R.color.cover_partial;
                repair = true;
                break;
            case DAMAGED:
                label = R.string.state_download_damaged;
                icon = R.drawable.ic_cover_exclamation_circle;
                color = R.color.cover_error;
                repair = true;
                break;
            case UNVERIFIED:
                if (state.hasLocalFiles) {
                    label = R.string.state_download_unverified;
                    icon = R.drawable.ic_cover_question_circle;
                    color = R.color.cover_unknown;
                }
                break;
        }
        int fileLabel = label;
        boolean allowRepair = repair;
        downloadProgress.setVisibility(View.GONE);
        if (task != null) {
            GalleryDownloaderV2 downloader = task;
            label = GalleryCardActions.taskLabel(downloader);
            switch (downloader.getStatus()) {
                case DOWNLOADING:
                    color = R.color.cover_downloading;
                    icon = R.drawable.ic_cover_download;
                    downloadProgress.setProgressCompat(downloader.getPercentage(), false);
                    downloadProgress.setVisibility(View.VISIBLE);
                    break;
                case PAUSED:
                    color = R.color.cover_partial;
                    icon = R.drawable.ic_cover_pause_circle;
                    break;
                case FAILED:
                    color = R.color.cover_error;
                    icon = R.drawable.ic_cover_exclamation_circle;
                    break;
                default:
                    color = R.color.cover_unknown;
                    icon = R.drawable.ic_cover_clock;
            }
        localFiles.setOnClickListener(view -> GalleryCardActions.task(view, downloader));
        } else if (state.hasLocalFiles || state.local == com.maxwai.nclientv3.files.LocalDownloadState.COMPLETE) {
            localFiles.setOnClickListener(view -> GalleryCardActions.files(item, gallery, fileLabel,
                state.local == com.maxwai.nclientv3.files.LocalDownloadState.UNVERIFIED
                    ? R.string.state_download_unverified_help : 0,
                allowRepair, () -> binding == renderedBinding, this::refresh));
        } else {
            localFiles.setOnClickListener(view -> {
                localFiles.setEnabled(false);
                GalleryCardActions.download(item, gallery, () -> binding == renderedBinding, this::refresh);
            });
        }
        localFiles.setEnabled(gallery != null && (id > 0 || state.hasLocalFiles) || task != null);
        localFiles.setVisibility(View.VISIBLE);
        localFiles.setImageResource(icon);
        tint(localFiles, color);
        String description = task != null && task.getStatus() == GalleryDownloaderV2.Status.DOWNLOADING
            ? context.getString(R.string.cover_download_running, task.getPercentage()) : context.getString(label);
        localFiles.setContentDescription(description);
        localFiles.setTooltipText(description);
    }

    private static void tint(ImageView button, int color) {
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(button.getContext().getColor(color)));
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        GalleryStateRepository.observe(refresh);
        if (task != null) task.addObserver(observer);
        refresh();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        binding++;
        request++;
        favoritePending = false;
        onlineFavoritePending = false;
        if (task != null) task.removeObserver(observer);
        main.removeCallbacksAndMessages(null);
        GalleryStateRepository.removeObserver(refresh);
    }
}
