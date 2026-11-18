// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
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
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.DebugImageMask;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class ContinuousPageAdapter extends RecyclerView.Adapter<ContinuousPageAdapter.Holder> {
    private static final int MAX_DECODE_WIDTH = 2048;
    private static final int MAX_DECODE_HEIGHT = 4096;
    private static final int PAGE_GAP_DP = 8;
    private static final Executor RETRY_EXECUTOR = Executors.newSingleThreadExecutor();

    private final GenericGallery gallery;
    @Nullable private GalleryFolder directory;
    @Nullable private final File directoryPath;
    private final BiConsumer<Integer, Float> onPageTap;
    @Nullable private OnPageDoubleTapListener onPageDoubleTap;
    private final Executor retryExecutor;
    private final ReaderPageRotations rotations;
    private int filterMode = ReaderImageFilter.NONE;
    private boolean autoCrop;
    private int brightness;
    private int contrast;
    private int saturation;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private RecyclerView recycler;

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
        holder.doubleTapListener = onPageDoubleTap;
        holder.retry.setOnClickListener(view -> {
            int page = holder.getBindingAdapterPosition();
            if (page != RecyclerView.NO_POSITION) retry(holder, page);
        });
        load(holder, position);
        holder.applyAppearance(filterMode, brightness, contrast, saturation);
    }

    private int pageGapPx(@NonNull Context context) {
        return Math.round(PAGE_GAP_DP * context.getResources().getDisplayMetrics().density);
    }

    public void setOnPageDoubleTapListener(@Nullable OnPageDoubleTapListener listener) {
        onPageDoubleTap = listener;
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
            if (page != RecyclerView.NO_POSITION) load((Holder) viewHolder, page);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        recycler = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (recycler == recyclerView) recycler = null;
        super.onDetachedFromRecyclerView(recyclerView);
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
        if (directoryPath == null) {
            load(holder, page);
            return;
        }
        int generation = ++holder.generation;
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
                adapter.directory = result;
                adapter.load(current, page);
            });
        });
    }

    private void load(Holder holder, int position) {
        RequestManager manager = GlideX.with(holder.image);
        holder.generation++;
        int generation = holder.generation;
        if (manager != null) manager.clear(holder.image);
        holder.image.setImageDrawable(null);
        Object source = pageSource(gallery, directory, position);
        holder.source = source;
        if (source == null || manager == null) {
            holder.showError();
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
                    holder.showError();
                    return false;
                }

                @Override
                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                               @NonNull Target<Drawable> target, @NonNull DataSource dataSource,
                                               boolean firstResource) {
                    if (generation != holder.generation) return true;
                    holder.showImage();
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
        holder.cancelSingleTap();
        holder.suppressNextClick = false;
        holder.generation++;
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
        @Nullable Object source;
        float tapPosition = 0.5f;
        @Nullable OnPageDoubleTapListener doubleTapListener;
        @Nullable Runnable pendingSingleTap;
        boolean suppressNextClick;
        final Handler gestureHandler = new Handler(Looper.getMainLooper());

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
                    if (view.getWidth() > 0) tapPosition = Math.max(0f, Math.min(1f, event.getX() / view.getWidth()));
                    return false;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent event) {
                    if (status.getVisibility() != View.GONE || image.getDrawable() == null) return false;
                    suppressNextClick = true;
                    cancelSingleTap();
                    gestureHandler.postDelayed(() -> suppressNextClick = false,
                        ViewConfiguration.getDoubleTapTimeout());
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
            gestureHandler.postDelayed(() -> {
                Runnable pending = pendingSingleTap;
                pendingSingleTap = null;
                if (pending != null) pending.run();
            }, ViewConfiguration.getDoubleTapTimeout());
        }

        void cancelSingleTap() {
            pendingSingleTap = null;
            gestureHandler.removeCallbacksAndMessages(null);
        }

        void showLoading() {
            status.setVisibility(View.VISIBLE);
            loading.setVisibility(View.VISIBLE);
            error.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
        }

        void showError() {
            status.setVisibility(View.VISIBLE);
            loading.setVisibility(View.GONE);
            ReaderLoadErrorMessage.apply(error);
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
