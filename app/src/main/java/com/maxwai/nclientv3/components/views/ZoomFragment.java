// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.views;

import static com.bumptech.glide.request.target.Target.SIZE_ORIGINAL;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
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
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.DebugImageMask;

public class ZoomFragment extends Fragment {

    private static final float MAX_SCALE = 8f;
    private PhotoView photoView = null;
    private View loadingView;
    private View errorView;
    private PageFile pageFile = null;
    private Uri url;
    private int degree = 0;
    private int scaleMode = ReaderScaleMode.SMART;
    private boolean completedDownload = false;
    private ImageViewTarget<Drawable> target = null;


    public ZoomFragment() {
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory) {
        return newInstance(gallery, page, directory, ReaderScaleMode.SMART);
    }

    public static ZoomFragment newInstance(GenericGallery gallery, int page, @Nullable GalleryFolder directory,
                                           int scaleMode) {
        Bundle args = new Bundle();
        args.putString("URL", gallery.isLocal() ? null : ((Gallery) gallery).getPageUrl(page).toString());
        args.putParcelable("FOLDER", directory == null ? null : directory.getPage(page + 1));
        args.putInt("SCALE_MODE", ReaderScaleMode.sanitize(scaleMode));
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
        //read arguments
        String str = getArguments().getString("URL");
        url = str == null ? null : Uri.parse(str);
        scaleMode = ReaderScaleMode.sanitize(getArguments().getInt("SCALE_MODE", ReaderScaleMode.SMART));
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

        photoView.setMaximumScale(MAX_SCALE);
        rootView.findViewById(R.id.reader_retry).setOnClickListener(v -> loadImage());
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
                errorView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                setResource(resource);
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
        photoView.setScale(calculateScaleFactor(drawable), 0, 0, false);
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
        if (photoView != null && photoView.getDrawable() != null) scalePhoto(photoView.getDrawable());
    }

    public void loadImage() {
        loadImage(Priority.NORMAL);
    }

    public void loadImage(Priority priority) {
        if (completedDownload || photoView == null || target == null) return;
        cancelRequest();
        if (pageFile == null && url == null) {
            target.onLoadFailed(null);
            return;
        }
        RequestBuilder<Drawable> dra = loadPage();
        if (dra == null) return;
        dra
            .transform(new Rotate(degree))
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
        if (completedDownload) return;
        if (photoView != null && target != null) {
            RequestManager manager = GlideX.with(photoView);
            if (manager != null) manager.clear(target);
        }
    }

    @Override
    public void onDestroyView() {
        if (photoView != null && target != null) {
            RequestManager manager = GlideX.with(photoView);
            if (manager != null) manager.clear(target);
        }
        completedDownload = false;
        target = null;
        photoView = null;
        loadingView = null;
        errorView = null;
        super.onDestroyView();
    }

    private void updateDegree() {
        degree = (degree + 270) % 360;
        completedDownload = false;
        loadImage();
    }

    public void rotate() {
        updateDegree();
    }
}
