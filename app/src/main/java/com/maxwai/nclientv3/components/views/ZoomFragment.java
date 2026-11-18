// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.views;

import static com.bumptech.glide.request.target.Target.SIZE_ORIGINAL;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.ImageViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.ZoomActivity;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.components.reader.ReaderScaleMode;
import com.maxwai.nclientv3.components.reader.ReaderImageFilter;
import com.maxwai.nclientv3.components.reader.ReaderAutoCropTransformation;
import com.maxwai.nclientv3.components.reader.ReaderLoadErrorMessage;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.DebugImageMask;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ZoomFragment extends Fragment {

    private static final float MAX_SCALE = 8f;
    private static final Executor RETRY_EXECUTOR = Executors.newSingleThreadExecutor();
    private Executor retryExecutor = RETRY_EXECUTOR;
    private int retryGeneration;
    private boolean retryPending;
    private PhotoView photoView = null;
    private View loadingView;
    private View errorView;
    private TextView errorMessage;
    private PageFile pageFile = null;
    private Uri url;
    private int pageIndex = -1;
    private int degree = 0;
    private int scaleMode = ReaderScaleMode.SMART;
    private int filterMode = ReaderImageFilter.NONE;
    private boolean autoCrop;
    private int brightness;
    private int contrast;
    private int saturation;
    private float initialScale;
    private boolean completedDownload = false;
    private ImageViewTarget<Drawable> target = null;


    public ZoomFragment() {
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory) {
        return newInstance(gallery, page, directory, ReaderScaleMode.SMART);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode) {
        return newInstance(gallery, page, directory, scaleMode, ReaderImageFilter.NONE);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode, int filterMode) {
        return newInstance(gallery, page, directory, scaleMode, filterMode, false);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode, int filterMode, boolean autoCrop) {
        return newInstance(gallery, page, directory, scaleMode, filterMode, autoCrop, 0, 0, 0);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode, int filterMode, boolean autoCrop,
                                           int brightness, int contrast, int saturation) {
        return newInstance(gallery, page, directory, scaleMode, filterMode, autoCrop,
            brightness, contrast, saturation, 0f);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode, int filterMode, boolean autoCrop,
                                           int brightness, int contrast, int saturation, float initialScale) {
        Bundle args = new Bundle();
        args.putString("URL", gallery.isLocal() ? null : ((Gallery) gallery).getPageUrl(page).toString());
        args.putParcelable("FOLDER", directory == null ? null : directory.getPage(page + 1));
        args.putString("DIRECTORY_PATH", directory == null ? null : directory.getFolder().getAbsolutePath());
        args.putInt("PAGE", page);
        args.putInt("SCALE_MODE", ReaderScaleMode.sanitize(scaleMode));
        args.putInt("FILTER_MODE", ReaderImageFilter.sanitize(filterMode));
        args.putBoolean("AUTO_CROP", autoCrop);
        args.putInt("BRIGHTNESS", ReaderImageFilter.sanitizeAdjustment(brightness));
        args.putInt("CONTRAST", ReaderImageFilter.sanitizeAdjustment(contrast));
        args.putInt("SATURATION", ReaderImageFilter.sanitizeAdjustment(saturation));
        args.putFloat("INITIAL_SCALE", initialScale);
        ZoomFragment fragment = new ZoomFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_zoom, container, false);
        ZoomActivity activity = (ZoomActivity) getActivity();
        assert getArguments() != null;
        assert activity != null;
        //find views
        photoView = rootView.findViewById(R.id.image);
        loadingView = rootView.findViewById(R.id.reader_loading);
        errorView = rootView.findViewById(R.id.reader_load_error);
        errorMessage = rootView.findViewById(R.id.reader_load_error_message);
        //read arguments
        String str = getArguments().getString("URL");
        url = str == null ? null : Uri.parse(str);
        pageIndex = getArguments().getInt("PAGE", -1);
        scaleMode = ReaderScaleMode.sanitize(getArguments().getInt("SCALE_MODE", ReaderScaleMode.SMART));
        filterMode = ReaderImageFilter.sanitize(getArguments().getInt("FILTER_MODE", ReaderImageFilter.NONE));
        autoCrop = getArguments().getBoolean("AUTO_CROP", false);
        brightness = ReaderImageFilter.sanitizeAdjustment(getArguments().getInt("BRIGHTNESS", 0));
        contrast = ReaderImageFilter.sanitizeAdjustment(getArguments().getInt("CONTRAST", 0));
        saturation = ReaderImageFilter.sanitizeAdjustment(getArguments().getInt("SATURATION", 0));
        initialScale = sanitizeInitialScale(getArguments().getFloat("INITIAL_SCALE", 0f));
        photoView.setContentDescription(getString(R.string.bookmark_page_format, pageIndex + 1));
        photoView.setClickable(true);
        ViewCompat.replaceAccessibilityAction(photoView,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.reader_tap_action_controls),
            (view, arguments) -> activity.onReaderAccessibilityToggleControls(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pageFile = getArguments().getParcelable("FOLDER", PageFile.class);
        } else {
            pageFile = getArguments().getParcelable("FOLDER");
        }
        photoView.setAllowParentInterceptOnEdge(true);
        photoView.setParentInterceptVertical(activity.isVerticalPageTurn());
        photoView.setOnViewTapListener((view, x, y) -> {
            if (view.getWidth() > 0) activity.onReaderPageTap(this, x / view.getWidth());
        });
        photoView.setOnLongClickListener(view -> {
            View settings = activity.findViewById(R.id.reader_settings);
            if (settings == null) return false;
            settings.performClick();
            return true;
        });

        photoView.setMaximumScale(MAX_SCALE);
        photoView.setOnScaleChangeListener((factor, focusX, focusY) -> {
            if (getActivity() instanceof ZoomActivity) {
                ((ZoomActivity) getActivity()).onReaderScaleChanged(photoView.getScale());
            }
        });
        rootView.findViewById(R.id.reader_retry).setOnClickListener(v -> retryPage());
        createTarget();
        loadImage();
        return rootView;
    }

    private void createTarget() {
        target = new ImageViewTarget<Drawable>(photoView) {

            @Override
            protected void setResource(@Nullable Drawable resource) {
                photoView.setImageDrawable(DebugImageMask.wrap(photoView.getContext(), resource));
            }

            @Override
            public void onLoadStarted(@Nullable Drawable placeholder) {
                super.onLoadStarted(placeholder);
                loadingView.setVisibility(View.VISIBLE);
                errorView.setVisibility(View.GONE);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                super.onLoadFailed(errorDrawable);
                loadingView.setVisibility(View.GONE);
                ReaderLoadErrorMessage.apply(errorMessage, pageFile == null && url != null);
                errorView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                setResource(resource);
                applyAppearance();
                loadingView.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
                scalePhoto(resource);
                if (resource instanceof Animatable)
                    ((Animatable) resource).start();
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                super.onLoadCleared(placeholder);
                loadingView.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
            }
        };
    }

    private void scalePhoto(Drawable drawable) {
        photoView.setScale(initialScale > 0f ? initialScale : calculateScaleFactor(drawable), 0, 0, false);
        initialScale = 0f;
    }

    private float sanitizeInitialScale(float value) {
        return value >= 0.1f && value <= MAX_SCALE ? value : 0f;
    }

    private float calculateScaleFactor(@NonNull Drawable drawable) {
        FragmentActivity activity = getActivity();
        int deviceWidth = activity == null ? 0 : Global.getDeviceWidth(activity);
        int deviceHeight = activity == null ? 0 : Global.getDeviceHeight(activity);
        int viewWidth = photoView.getWidth() > 0 ? photoView.getWidth() : deviceWidth;
        int viewHeight = photoView.getHeight() > 0 ? photoView.getHeight() : deviceHeight;
        return ReaderScaleMode.calculate(scaleMode, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
            viewWidth, viewHeight, deviceWidth, deviceHeight,
            Global.getDefaultZoom(), MAX_SCALE);
    }

    public void setScaleMode(int mode) {
        scaleMode = ReaderScaleMode.sanitize(mode);
        requireArguments().putInt("SCALE_MODE", scaleMode);
        if (photoView != null && photoView.getDrawable() != null) scalePhoto(photoView.getDrawable());
    }

    public void setFilterMode(int mode) {
        filterMode = ReaderImageFilter.sanitize(mode);
        requireArguments().putInt("FILTER_MODE", filterMode);
        applyAppearance();
    }

    public void setAdjustments(int brightness, int contrast, int saturation) {
        this.brightness = ReaderImageFilter.sanitizeAdjustment(brightness);
        this.contrast = ReaderImageFilter.sanitizeAdjustment(contrast);
        this.saturation = ReaderImageFilter.sanitizeAdjustment(saturation);
        requireArguments().putInt("BRIGHTNESS", this.brightness);
        requireArguments().putInt("CONTRAST", this.contrast);
        requireArguments().putInt("SATURATION", this.saturation);
        applyAppearance();
    }

    private void applyAppearance() {
        if (photoView != null) ReaderImageFilter.apply(photoView, filterMode, brightness, contrast, saturation);
    }

    public void setAutoCrop(boolean enabled) {
        requireArguments().putBoolean("AUTO_CROP", enabled);
        if (autoCrop == enabled) return;
        autoCrop = enabled;
        completedDownload = false;
        loadImage(Priority.NORMAL, true);
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void loadImage() {
        loadImage(Priority.NORMAL);
    }

    public void loadImage(Priority priority) {
        loadImage(priority, false);
    }

    private void retryPage() {
        String path = requireArguments().getString("DIRECTORY_PATH");
        if (path == null) {
            loadImage();
            return;
        }
        int generation = ++retryGeneration;
        retryPending = true;
        ImageViewTarget<Drawable> expectedTarget = target;
        clearRequest();
        completedDownload = false;
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        int requestedPage = pageIndex + 1;
        Handler handler = new Handler(Looper.getMainLooper());
        WeakReference<ZoomFragment> reference = new WeakReference<>(this);
        retryExecutor.execute(() -> {
            PageFile refreshed;
            try {
                refreshed = new GalleryFolder(new File(path)).getPage(requestedPage);
            } catch (IllegalArgumentException | SecurityException exception) {
                refreshed = null;
            }
            PageFile result = refreshed;
            handler.post(() -> {
                ZoomFragment fragment = reference.get();
                if (fragment == null || fragment.retryGeneration != generation
                    || fragment.target != expectedTarget || fragment.getView() == null) return;
                fragment.retryPending = false;
                fragment.pageFile = result;
                fragment.requireArguments().putParcelable("FOLDER", result);
                fragment.loadImage();
            });
        });
    }

    private void loadImage(Priority priority, boolean force) {
        if (completedDownload || photoView == null || target == null) return;
        if (!force && retryPending) return;
        com.bumptech.glide.request.Request current = target.getRequest();
        if (!force && current != null && current.isRunning()) return;
        retryGeneration++;
        retryPending = false;
        if (force) clearRequest();
        if (pageFile == null && url == null) {
            target.onLoadFailed(null);
            return;
        }
        RequestBuilder<Drawable> dra = loadPage();
        if (dra == null) return;
        if (autoCrop) {
            dra = dra.transform(new MultiTransformation<>(new Rotate(degree), new ReaderAutoCropTransformation()));
        } else {
            dra = dra.transform(new Rotate(degree));
        }
        dra
            .priority(priority)
            .addListener(new RequestListener<>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                    return false;
                }

                @Override
                public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                    completedDownload = true;
                    return false;
                }
            })
            .into(target);
    }

    @Nullable
    private RequestBuilder<Drawable> loadPage() {
        RequestBuilder<Drawable> request;
        RequestManager glide = GlideX.with(photoView);
        if (glide == null) return null;
        if (pageFile != null) {
            request = glide.load(pageFile);
            LogUtility.d("Requested file glide: " + pageFile);
        } else {
            LogUtility.d("Requested url glide: " + url);
            request = glide.load(url);
        }
        return request.override(SIZE_ORIGINAL);
    }

    @Nullable
    public Drawable getDrawable() {
        return completedDownload && photoView != null ? photoView.getDrawable() : null;
    }

    public void setVerticalPageTurn(boolean vertical) {
        if (photoView != null) photoView.setParentInterceptVertical(vertical);
    }

    public void cancelRequest() {
        retryGeneration++;
        retryPending = false;
        if (completedDownload) return;
        clearRequest();
    }

    private void clearRequest() {
        if (photoView != null && target != null) {
            RequestManager manager = GlideX.with(photoView);
            if (manager != null) manager.clear(target);
        }
    }

    @Override
    public void onDestroyView() {
        retryGeneration++;
        retryPending = false;
        if (photoView != null && target != null) {
            RequestManager manager = GlideX.with(photoView);
            if (manager != null) manager.clear(target);
        }
        completedDownload = false;
        target = null;
        photoView = null;
        loadingView = null;
        errorView = null;
        errorMessage = null;
        super.onDestroyView();
    }

    private void updateDegree() {
        degree = (degree + 270) % 360;
        completedDownload = false;
        loadImage(Priority.NORMAL, true);
    }

    public void rotate() {
        updateDegree();
    }
}
