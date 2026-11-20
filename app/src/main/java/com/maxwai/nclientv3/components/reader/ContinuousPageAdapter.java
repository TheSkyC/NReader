// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.DebugImageMask;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ContinuousPageAdapter extends RecyclerView.Adapter<ContinuousPageAdapter.Holder> {
    private static final int MAX_DECODE_WIDTH = 2048;
    private static final int MAX_DECODE_HEIGHT = 4096;
    private static final int PAGE_GAP_DP = 8;
    // Limited to static local PNG pages; unsupported sources retain the Glide path.
    private static final boolean ENABLE_TILED_PAGES = true;
    private static final Executor RETRY_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Executor TILE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SNAPSHOT_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long TILE_RETRY_DELAY_MS = 2000;
    @Nullable private static ContinuousTileSnapshotStore snapshotStore;
    private static boolean snapshotLoaderRegistered;

    private final GenericGallery gallery;
    @Nullable private GalleryFolder directory;
    @Nullable private final File directoryPath;
    private final BiConsumer<Integer, Float> onPageTap;
    @Nullable private OnPageDoubleTapListener onPageDoubleTap;
    @Nullable private Runnable onAccessibilityToggleControls;
    @Nullable private BiConsumer<View, Runnable> imageTransition;
    @Nullable private Consumer<View> imageTransitionCancellation;
    private final Executor retryExecutor;
    private final ReaderPageRotations rotations;
    private int filterMode = ReaderImageFilter.NONE;
    private boolean autoCrop;
    private int brightness;
    private int contrast;
    private int saturation;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ContinuousTileCache tileCache = new ContinuousTileCache(16 * 1024 * 1024, 8,
        ContinuousTileDecoder.MAX_TILE_BYTES);
    private final ContinuousTileOpener<ContinuousTileSnapshotSession> tileOpener =
        new ContinuousTileOpener<>(TILE_EXECUTOR, mainHandler::post);
    private boolean tilesActive;
    @Nullable private RecyclerView recycler;
    private final RecyclerView.OnScrollListener tapCancellationListener = new RecyclerView.OnScrollListener() {
        @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) cancelVisiblePageTaps(view);
        }

        @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
            if (dx != 0 || dy != 0) cancelVisiblePageTaps(view);
            for (int index = 0; index < view.getChildCount(); index++) {
                RecyclerView.ViewHolder holder = view.getChildViewHolder(view.getChildAt(index));
                if (holder instanceof Holder && ((Holder) holder).tileDrawable != null)
                    ((Holder) holder).tileDrawable.invalidateSelf();
            }
        }
    };

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                                 @NonNull BiConsumer<Integer, Float> onPageTap) {
        this(gallery, directory, onPageTap, RETRY_EXECUTOR, new ReaderPageRotations());
    }

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                                 @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull ReaderPageRotations rotations) {
        this(gallery, directory, onPageTap, RETRY_EXECUTOR, rotations);
    }

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                          @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull Executor retryExecutor) {
        this(gallery, directory, onPageTap, retryExecutor, new ReaderPageRotations());
    }

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                          @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull ReaderPageRotations rotations,
                          int filterMode) {
        this(gallery, directory, onPageTap, RETRY_EXECUTOR, rotations);
        this.filterMode = ReaderImageFilter.sanitize(filterMode);
    }

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                          @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull ReaderPageRotations rotations,
                          int filterMode, boolean autoCrop) {
        this(gallery, directory, onPageTap, RETRY_EXECUTOR, rotations);
        this.filterMode = ReaderImageFilter.sanitize(filterMode);
        this.autoCrop = autoCrop;
    }

    public ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                          @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull ReaderPageRotations rotations,
                          int filterMode, boolean autoCrop, int brightness, int contrast, int saturation) {
        this(gallery, directory, onPageTap, RETRY_EXECUTOR, rotations);
        this.filterMode = ReaderImageFilter.sanitize(filterMode);
        this.autoCrop = autoCrop;
        setAdjustmentValues(brightness, contrast, saturation);
    }

    private ContinuousPageAdapter(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory,
                          @NonNull BiConsumer<Integer, Float> onPageTap, @NonNull Executor retryExecutor,
                          @NonNull ReaderPageRotations rotations) {
        this.gallery = gallery;
        this.directory = directory;
        this.directoryPath = directory == null ? null : directory.getFolder();
        this.onPageTap = onPageTap;
        this.retryExecutor = retryExecutor;
        this.rotations = rotations;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.continuous_reader_page, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.cancelSingleTap();
        holder.gestureHandler.removeCallbacks(holder.clearClickSuppression);
        holder.suppressNextClick = false;
        ViewGroup.LayoutParams rawParams = holder.itemView.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            params.bottomMargin = position == getItemCount() - 1 ? 0 : pageGapPx(holder.itemView.getContext());
            holder.itemView.setLayoutParams(params);
        }
        holder.itemView.setMinimumHeight(position == getItemCount() - 1 ? viewportHeight() : 0);
        holder.tapPosition = 0.5f;
        holder.itemView.setContentDescription(holder.itemView.getContext().getString(R.string.bookmark_page_format, position + 1));
        holder.itemView.setOnClickListener(view -> {
            if (holder.suppressNextClick) {
                holder.suppressNextClick = false;
                holder.cancelSingleTap();
                return;
            }
            int page = holder.getBindingAdapterPosition();
            float horizontalPosition = holder.tapPosition;
            holder.tapPosition = 0.5f;
            if (page != RecyclerView.NO_POSITION) {
                holder.scheduleSingleTap(() -> onPageTap.accept(page, horizontalPosition));
            }
        });
        updateAccessibilityAction(holder);
        holder.doubleTapListener = onPageDoubleTap;
        holder.retry.setOnClickListener(view -> {
            int page = holder.getBindingAdapterPosition();
            if (page != RecyclerView.NO_POSITION) retry(holder, page);
        });
        load(holder, position);
        holder.applyAppearance(filterMode, brightness, contrast, saturation);
    }

    private void updateAccessibilityAction(@NonNull Holder holder) {
        if (onAccessibilityToggleControls != null) {
            ViewCompat.replaceAccessibilityAction(holder.itemView,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                holder.itemView.getContext().getString(R.string.reader_tap_action_controls), (view, arguments) -> {
                    if (holder.getBindingAdapter() != this || holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION
                        || onAccessibilityToggleControls == null) return false;
                    holder.cancelSingleTap();
                    onAccessibilityToggleControls.run();
                    return true;
                });
        } else {
            ViewCompat.removeAccessibilityAction(holder.itemView, AccessibilityNodeInfoCompat.ACTION_CLICK);
        }
    }

    private int pageGapPx(@NonNull Context context) {
        return Math.round(PAGE_GAP_DP * context.getResources().getDisplayMetrics().density);
    }

    public void setOnPageDoubleTapListener(@Nullable OnPageDoubleTapListener listener) {
        onPageDoubleTap = listener;
    }

    public void setImageTransition(@Nullable BiConsumer<View, Runnable> transition,
                                   @Nullable Consumer<View> cancellation) {
        imageTransition = transition;
        imageTransitionCancellation = cancellation;
    }

    private void cancelImageTransition(@NonNull Holder holder) {
        if (imageTransitionCancellation != null) imageTransitionCancellation.accept(holder.itemView);
    }

    public void setOnAccessibilityToggleControls(@Nullable Runnable listener) {
        onAccessibilityToggleControls = listener;
        if (recycler == null) return;
        for (int index = 0; index < recycler.getChildCount(); index++) {
            RecyclerView.ViewHolder holder = recycler.getChildViewHolder(recycler.getChildAt(index));
            if (holder instanceof Holder) updateAccessibilityAction((Holder) holder);
        }
    }

    public void setFilterMode(int mode) {
        filterMode = ReaderImageFilter.sanitize(mode);
        if (recycler == null) return;
        for (int index = 0; index < recycler.getChildCount(); index++) {
            RecyclerView.ViewHolder viewHolder = recycler.getChildViewHolder(recycler.getChildAt(index));
            if (viewHolder instanceof Holder)
                ((Holder) viewHolder).applyAppearance(filterMode, brightness, contrast, saturation);
        }
    }

    public void setAdjustments(int brightness, int contrast, int saturation) {
        setAdjustmentValues(brightness, contrast, saturation);
        if (recycler == null) return;
        for (int index = 0; index < recycler.getChildCount(); index++) {
            RecyclerView.ViewHolder viewHolder = recycler.getChildViewHolder(recycler.getChildAt(index));
            if (viewHolder instanceof Holder)
                ((Holder) viewHolder).applyAppearance(filterMode, this.brightness, this.contrast, this.saturation);
        }
    }

    private void setAdjustmentValues(int brightness, int contrast, int saturation) {
        this.brightness = ReaderImageFilter.sanitizeAdjustment(brightness);
        this.contrast = ReaderImageFilter.sanitizeAdjustment(contrast);
        this.saturation = ReaderImageFilter.sanitizeAdjustment(saturation);
    }

    public void setAutoCrop(boolean enabled) {
        if (autoCrop == enabled) return;
        autoCrop = enabled;
        if (recycler == null) return;
        for (int index = 0; index < recycler.getChildCount(); index++) {
            RecyclerView.ViewHolder viewHolder = recycler.getChildViewHolder(recycler.getChildAt(index));
            if (!(viewHolder instanceof Holder)) continue;
            int page = viewHolder.getBindingAdapterPosition();
            if (page != RecyclerView.NO_POSITION && !((Holder) viewHolder).retryPending) load((Holder) viewHolder, page);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        recycler = recyclerView;
        recyclerView.addOnScrollListener(tapCancellationListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        cancelVisiblePageTaps(recyclerView);
        for (int index = 0; index < recyclerView.getChildCount(); index++) {
            RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index));
            if (holder instanceof Holder) {
                cancelImageTransition((Holder) holder);
                clearTiles((Holder) holder);
            }
        }
        recyclerView.removeOnScrollListener(tapCancellationListener);
        if (recycler == recyclerView) recycler = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    private void cancelVisiblePageTaps(@NonNull RecyclerView view) {
        for (int index = 0; index < view.getChildCount(); index++) {
            RecyclerView.ViewHolder holder = view.getChildViewHolder(view.getChildAt(index));
            if (holder instanceof Holder) ((Holder) holder).cancelSingleTap();
        }
    }

    void cancelPendingPageTaps() {
        if (recycler != null) cancelVisiblePageTaps(recycler);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull Holder holder) {
        holder.cancelSingleTap();
        cancelImageTransition(holder);
        clearTiles(holder);
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull Holder holder) {
        super.onViewAttachedToWindow(holder);
        maybeStartTiles(holder);
    }

    public void setTilesActive(boolean active) {
        if (tilesActive == active) return;
        tilesActive = active;
        if (recycler == null) return;
        for (int index = 0; index < recycler.getChildCount(); index++) {
            RecyclerView.ViewHolder viewHolder = recycler.getChildViewHolder(recycler.getChildAt(index));
            if (!(viewHolder instanceof Holder)) continue;
            Holder holder = (Holder) viewHolder;
            if (active) maybeStartTiles(holder);
            else clearTiles(holder, true);
        }
        if (!active) tileCache.clear();
    }

    private void clearTiles(@NonNull Holder holder) {
        clearTiles(holder, false);
    }

    private void clearTiles(@NonNull Holder holder, boolean keepPosition) {
        holder.tileGeneration++;
        if (holder.tileRetry != null) mainHandler.removeCallbacks(holder.tileRetry);
        holder.tileRetry = null;
        if (holder.tileRequest != null) tileOpener.cancel(holder.tileRequest);
        holder.tileRequest = null;
        ContinuousTileDrawable drawable = holder.tileDrawable;
        holder.tileDrawable = null;
        Drawable previous = holder.tileFallback;
        holder.tileFallback = null;
        if (drawable != null) {
            boolean restore = holder.image.getDrawable() == drawable;
            drawable.close();
            if (restore) {
                Drawable fallback = previous != null ? previous : drawable.fallback();
                Runnable change = () -> holder.image.setImageDrawable(fallback);
                if (keepPosition && recycler != null && holder.itemView.isAttachedToWindow()
                    && holder.getBindingAdapter() == this && imageTransition != null)
                    imageTransition.accept(holder.itemView, change);
                else change.run();
            }
        }
        if (holder.tileTarget != null && holder.tileManager != null) holder.tileManager.clear(holder.tileTarget);
        holder.tileTarget = null;
        holder.tileManager = null;
        ContinuousTileSnapshotSession session = holder.tileSession;
        holder.tileSession = null;
        if (session != null) releaseSession(session);
    }

    private static void releaseSession(@NonNull ContinuousTileSnapshotSession session) {
        TILE_EXECUTOR.execute(() -> {
            try {
                session.close();
            } catch (IOException exception) {
                LogUtility.w("Could not release image snapshot", exception);
            }
        });
    }

    private static synchronized ContinuousTileSnapshotStore snapshotStore(@NonNull File cacheDirectory) throws IOException {
        if (snapshotStore == null) snapshotStore = ContinuousTileSnapshotStore.open(
            new File(cacheDirectory, "reader-tile-snapshots"), MAX_SNAPSHOT_BYTES, MAX_SNAPSHOT_TOTAL_BYTES);
        return snapshotStore;
    }

    private static synchronized void registerSnapshotLoader(@NonNull Context context) {
        if (snapshotLoaderRegistered) return;
        Glide.get(context.getApplicationContext()).getRegistry().prepend(
            ContinuousTileSnapshotSession.class, InputStream.class, new ContinuousTileSnapshotLoader.Factory());
        snapshotLoaderRegistered = true;
    }

    private void maybeStartTiles(@NonNull Holder holder) {
        int page = holder.getBindingAdapterPosition();
        Drawable fallback = holder.image.getDrawable();
        if (!ENABLE_TILED_PAGES || !tilesActive || recycler == null || !holder.itemView.isAttachedToWindow()
            || holder.getBindingAdapter() != this || page == RecyclerView.NO_POSITION
            || holder.status.getVisibility() != View.GONE || holder.tileRequest != null
            || holder.tileDrawable != null || holder.tileSession != null || !(holder.source instanceof PageFile) || fallback == null
            || autoCrop || rotations.angle(page) != 0 || DebugImageMask.isEnabled(holder.image.getContext())) return;
        File file = (File) holder.source;
        int generation = ++holder.tileGeneration;
        File cacheDirectory = holder.image.getContext().getApplicationContext().getCacheDir();
        holder.tileRequest = tileOpener.requestCancellable(canceled -> ContinuousTileSnapshotSession.open(
            snapshotStore(cacheDirectory), file, canceled, MAX_DECODE_HEIGHT,
            error -> LogUtility.w("Could not release image snapshot", error)), session -> {
            if (generation == holder.tileGeneration) holder.tileRequest = null;
            if (session == null) return;
            if (generation != holder.tileGeneration || !tilesActive || holder.getBindingAdapter() != this
                || holder.getBindingAdapterPosition() != page || !holder.itemView.isAttachedToWindow()
                || holder.image.getDrawable() != fallback || autoCrop || rotations.angle(page) != 0
                || DebugImageMask.isEnabled(holder.image.getContext())) {
                releaseSession(session);
                return;
            }
            RequestManager manager = GlideX.with(holder.image);
            if (manager == null) {
                releaseSession(session);
                return;
            }
            holder.tileSession = session;
            holder.tileFallback = fallback;
            holder.tileManager = manager;
            CustomTarget<Drawable> target = new CustomTarget<Drawable>() {
                @Override public void onResourceReady(@NonNull Drawable resource,
                                                       @Nullable Transition<? super Drawable> transition) {
                    if (generation != holder.tileGeneration || holder.tileSession != session
                        || holder.image.getDrawable() != fallback) {
                        mainHandler.post(() -> { if (holder.tileSession == session) clearTiles(holder); });
                        return;
                    }
                    ContinuousTileLoader.Source source = session.takeSource();
                    try {
                        ContinuousTileDrawable drawable = new ContinuousTileDrawable(resource, session.width(), session.height(),
                            8, source, tileCache, TILE_EXECUTOR, mainHandler::post, error -> {
                                LogUtility.w("Could not decode image tiles", error.getClass().getSimpleName());
                                clearTiles(holder, true);
                            },
                            () -> visibleDrawableBounds(holder.image));
                        holder.tileDrawable = drawable;
                        if (imageTransition == null) holder.image.setImageDrawable(drawable);
                        else imageTransition.accept(holder.itemView, () -> holder.image.setImageDrawable(drawable));
                    } catch (RuntimeException | OutOfMemoryError error) {
                        LogUtility.w("Could not display image tiles", error);
                        TILE_EXECUTOR.execute(source::close);
                        mainHandler.post(() -> { if (holder.tileSession == session) clearTiles(holder); });
                    }
                }

                @Override public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    mainHandler.post(() -> { if (holder.tileSession == session) clearTiles(holder); });
                }

                @Override public void onLoadCleared(@Nullable Drawable placeholder) {}
            };
            holder.tileTarget = target;
            int width = Math.max(1, Math.min(MAX_DECODE_WIDTH,
                holder.image.getContext().getResources().getDisplayMetrics().widthPixels));
            try {
                registerSnapshotLoader(holder.image.getContext());
                manager.load(session).override(width, MAX_DECODE_HEIGHT).fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).into(target);
            } catch (RuntimeException | OutOfMemoryError error) {
                LogUtility.w("Could not load image snapshot", error);
                clearTiles(holder);
            }
        }, error -> {
            if (generation != holder.tileGeneration) return;
            holder.tileRequest = null;
            if (error instanceof RejectedExecutionException
                || error instanceof ContinuousTileSnapshotStore.BudgetExceededException) {
                scheduleTileRetry(holder, generation);
            } else {
                LogUtility.w("Could not prepare image tiles", error.getClass().getSimpleName());
            }
        });
    }

    @NonNull
    private static Rect visibleDrawableBounds(@NonNull ImageView image) {
        Rect visible = new Rect();
        if (!image.getLocalVisibleRect(visible)) return new Rect();
        RectF drawableBounds = new RectF(visible);
        drawableBounds.offset(-image.getPaddingLeft(), -image.getPaddingTop());
        Matrix inverse = new Matrix();
        if (!image.getImageMatrix().invert(inverse)) return new Rect();
        inverse.mapRect(drawableBounds);
        Rect result = new Rect();
        drawableBounds.roundOut(result);
        return result;
    }

    private void scheduleTileRetry(@NonNull Holder holder, int generation) {
        if (holder.tileRetry != null) return;
        Runnable retry = () -> {
            holder.tileRetry = null;
            if (generation == holder.tileGeneration) maybeStartTiles(holder);
        };
        holder.tileRetry = retry;
        mainHandler.postDelayed(retry, TILE_RETRY_DELAY_MS);
    }

    private int viewportHeight() {
        return recycler == null ? 0
            : Math.max(0, recycler.getHeight() - recycler.getPaddingTop() - recycler.getPaddingBottom());
    }

    void updateViewportHeight(int height) {
        if (recycler == null || getItemCount() == 0) return;
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(getItemCount() - 1);
        if (holder != null && holder.itemView.getMinimumHeight() != height) {
            holder.itemView.setMinimumHeight(height);
            holder.itemView.requestLayout();
        }
    }

    private void retry(@NonNull Holder holder, int page) {
        cancelImageTransition(holder);
        clearTiles(holder);
        if (directoryPath == null) {
            load(holder, page);
            return;
        }
        int generation = ++holder.generation;
        holder.retryPending = true;
        holder.source = null;
        RequestManager manager = GlideX.with(holder.image);
        if (manager != null) manager.clear(holder.image);
        holder.image.setImageDrawable(null);
        holder.showLoading();
        File path = directoryPath;
        Handler handler = mainHandler;
        WeakReference<ContinuousPageAdapter> adapterRef = new WeakReference<>(this);
        WeakReference<Holder> holderRef = new WeakReference<>(holder);
        retryExecutor.execute(() -> {
            GalleryFolder refreshed;
            try {
                refreshed = new GalleryFolder(path);
            } catch (IllegalArgumentException | SecurityException exception) {
                refreshed = null;
            }
            GalleryFolder result = refreshed;
            handler.post(() -> {
                ContinuousPageAdapter adapter = adapterRef.get();
                Holder current = holderRef.get();
                if (adapter == null || current == null || current.generation != generation
                    || current.getBindingAdapter() != adapter || current.getBindingAdapterPosition() != page) return;
                current.retryPending = false;
                adapter.directory = result;
                adapter.load(current, page);
            });
        });
    }

    private void load(Holder holder, int position) {
        cancelImageTransition(holder);
        clearTiles(holder);
        RequestManager manager = GlideX.with(holder.image);
        holder.generation++;
        holder.retryPending = false;
        int generation = holder.generation;
        if (manager != null) manager.clear(holder.image);
        holder.image.setImageDrawable(null);
        Object source = pageSource(gallery, directory, position);
        holder.source = source;
        if (source == null || manager == null) {
            holder.showError(source == null && gallery instanceof Gallery);
            return;
        }
        holder.showLoading();
        Context context = holder.image.getContext();
        int width = Math.max(1, Math.min(MAX_DECODE_WIDTH, context.getResources().getDisplayMetrics().widthPixels));
        int angle = rotations.angle(position);
        boolean quarterTurn = angle == 90 || angle == 270;
        int targetWidth = quarterTurn ? MAX_DECODE_HEIGHT : width;
        int targetHeight = quarterTurn ? width : MAX_DECODE_HEIGHT;
        MultiTransformation<android.graphics.Bitmap> transforms = autoCrop
            ? new MultiTransformation<>(Arrays.asList(new FitCenter(), new Rotate(angle), new ReaderAutoCropTransformation()))
            : new MultiTransformation<>(Arrays.asList(new FitCenter(), new Rotate(angle)));
        manager.load(source).override(targetWidth, targetHeight).fitCenter()
            .transform(transforms)
            .addListener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException exception, Object model,
                                            @NonNull Target<Drawable> target, boolean firstResource) {
                    if (generation != holder.generation) return true;
                    holder.showError(source instanceof android.net.Uri);
                    return false;
                }

                @Override
                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                               @NonNull Target<Drawable> target, @NonNull DataSource dataSource,
                                               boolean firstResource) {
                    if (generation != holder.generation) return true;
                    holder.showImage();
                    holder.image.post(() -> {
                        if (generation == holder.generation) maybeStartTiles(holder);
                    });
                    return false;
                }
            }).into(DebugImageMask.target(holder.image));
    }

    public boolean rotate(@NonNull RecyclerView.ViewHolder viewHolder) {
        if (!(viewHolder instanceof Holder) || loadedSource(viewHolder) == null) return false;
        Holder holder = (Holder) viewHolder;
        int page = holder.getBindingAdapterPosition();
        if (holder.getBindingAdapter() != this || page == RecyclerView.NO_POSITION) return false;
        int evicted = rotations.rotate(page);
        load(holder, page);
        if (evicted >= 0) notifyItemChanged(evicted);
        return true;
    }

    @Nullable
    public Object loadedSource(@NonNull RecyclerView.ViewHolder viewHolder) {
        if (!(viewHolder instanceof Holder)) return null;
        Holder holder = (Holder) viewHolder;
        if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION
            || holder.status.getVisibility() != View.GONE || holder.image.getDrawable() == null) return null;
        return holder.source;
    }

    @Nullable
    public static Object pageSource(@NonNull GenericGallery gallery, @Nullable GalleryFolder directory, int position) {
        if (position < 0 || position >= gallery.getPageCount()) return null;
        PageFile local = directory == null ? null : directory.getPage(position + 1);
        if (local != null) return local;
        if (gallery instanceof Gallery && Global.getDownloadPolicy() != Global.DataUsageType.NONE)
            return ((Gallery) gallery).getPageUrl(position);
        return null;
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        cancelImageTransition(holder);
        clearTiles(holder);
        holder.cancelSingleTap();
        holder.gestureHandler.removeCallbacks(holder.clearClickSuppression);
        holder.suppressNextClick = false;
        holder.generation++;
        holder.retryPending = false;
        holder.source = null;
        RequestManager manager = GlideX.with(holder.image);
        if (manager != null) manager.clear(holder.image);
        holder.image.setImageDrawable(null);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return gallery.getPageCount();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View status;
        final View loading;
        final TextView error;
        final View retry;
        int generation;
        int tileGeneration;
        @Nullable ContinuousTileOpener.Request<ContinuousTileSnapshotSession> tileRequest;
        @Nullable ContinuousTileDrawable tileDrawable;
        @Nullable ContinuousTileSnapshotSession tileSession;
        @Nullable CustomTarget<Drawable> tileTarget;
        @Nullable RequestManager tileManager;
        @Nullable Drawable tileFallback;
        @Nullable Runnable tileRetry;
        boolean retryPending;
        @Nullable Object source;
        float tapPosition = 0.5f;
        @Nullable OnPageDoubleTapListener doubleTapListener;
        @Nullable Runnable pendingSingleTap;
        boolean suppressNextClick;
        final Handler gestureHandler = new Handler(Looper.getMainLooper());
        final Runnable clearClickSuppression = () -> suppressNextClick = false;
        final Runnable singleTapDispatch = () -> {
            Runnable pending = pendingSingleTap;
            pendingSingleTap = null;
            if (pending != null) pending.run();
        };

        @SuppressLint("ClickableViewAccessibility") // Touch observation does not consume events; the view performs clicks normally.
        Holder(View view) {
            super(view);
            image = view.findViewById(R.id.continuous_page_image);
            status = view.findViewById(R.id.continuous_page_status);
            loading = view.findViewById(R.id.continuous_page_loading);
            error = view.findViewById(R.id.continuous_page_error);
            retry = view.findViewById(R.id.continuous_page_retry);
            GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent event) {
                    tapPosition = 0.5f;
                    return true;
                }

                @Override
                public boolean onSingleTapUp(@NonNull MotionEvent event) {
                    if (view.getParent() instanceof RecyclerView) {
                        RecyclerView viewport = (RecyclerView) view.getParent();
                        if (viewport.getWidth() > 0) {
                            float viewportX = view.getX() + event.getX() - viewport.getScrollX();
                            tapPosition = Math.max(0f, Math.min(1f, viewportX / viewport.getWidth()));
                        }
                    } else if (view.getWidth() > 0) {
                        tapPosition = Math.max(0f, Math.min(1f, event.getX() / view.getWidth()));
                    }
                    return false;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent event) {
                    if (status.getVisibility() != View.GONE || image.getDrawable() == null) return false;
                    suppressNextClick = true;
                    cancelSingleTap();
                    gestureHandler.removeCallbacks(clearClickSuppression);
                    gestureHandler.postDelayed(clearClickSuppression, ViewConfiguration.getDoubleTapTimeout());
                    int page = getBindingAdapterPosition();
                    if (page != RecyclerView.NO_POSITION && doubleTapListener != null) {
                        doubleTapListener.onDoubleTap(page,
                            view.getLeft() + view.getTranslationX() + event.getX(),
                            view.getTop() + event.getY());
                    }
                    return true;
                }
            });
            view.setOnTouchListener((touched, event) -> {
                detector.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) tapPosition = 0.5f;
                return false;
            });
        }

        void scheduleSingleTap(@NonNull Runnable action) {
            cancelSingleTap();
            pendingSingleTap = action;
            gestureHandler.postDelayed(singleTapDispatch, ViewConfiguration.getDoubleTapTimeout());
        }

        void cancelSingleTap() {
            pendingSingleTap = null;
            gestureHandler.removeCallbacks(singleTapDispatch);
        }

        void showLoading() {
            status.setVisibility(View.VISIBLE);
            loading.setVisibility(View.VISIBLE);
            error.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
        }

        void showError(boolean networkPage) {
            status.setVisibility(View.VISIBLE);
            loading.setVisibility(View.GONE);
            ReaderLoadErrorMessage.apply(error, networkPage);
            error.setVisibility(View.VISIBLE);
            retry.setVisibility(View.VISIBLE);
        }

        void showImage() {
            status.setVisibility(View.GONE);
        }

        void applyAppearance(int mode, int brightness, int contrast, int saturation) {
            ReaderImageFilter.apply(image, mode, brightness, contrast, saturation);
        }
    }

    public interface OnPageDoubleTapListener {
        void onDoubleTap(int page, float x, float y);
    }
}
