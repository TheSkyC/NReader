// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.settings.Global;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class ImageDownloadUtility {

    private static final Map<Gallery, List<Runnable>> imageDownloadQueue = new HashMap<>();

    public static void preloadImage(Context context, Uri url) {
        if (Global.getDownloadPolicy() == Global.DataUsageType.NONE) return;
        RequestManager manager = GlideX.with(context);
        LogUtility.d("Requested url glide: " + url);
        if (manager != null) manager.load(url).preload();
    }

    public static void loadImageOp(Context context, ImageView view, File file, int angle) {
        RequestManager glide = GlideX.with(context);
        if (glide == null) return;
        Drawable logo = placeholder(view);
        glide.load(file).transform(new Rotate(angle)).error(logo).placeholder(logo).into(DebugImageMask.target(view));
        LogUtility.d("Requested file glide: " + file);
    }

    public static void loadImageOp(Context context, ImageView view, Gallery gallery, int page, int angle) {
        loadImageOp(context, view, gallery, page, angle, true);
    }

    public static void downloadPage(Activity activity, ImageView imageView, Gallery gallery, int page, boolean shouldFull) {
        shouldFull = gallery.getHighPage(page).toString().endsWith("gif") || shouldFull;
        loadImageOp(activity, imageView, gallery, page, 0, shouldFull);
    }

    public static void loadImageOp(Context context, ImageView imageView, Gallery gallery, int page, int angle, boolean shouldFull) {
        loadImageOp(context, imageView, gallery, () -> getUrlForGallery(gallery, page, shouldFull), angle, false);
    }

    private static void loadImageOp(Context context, ImageView view, @Nullable Gallery gallery, Supplier<Uri> url, int angle, boolean priority) {
        loadLogo(view);
        if (Global.getDownloadPolicy() == Global.DataUsageType.NONE) {
            return;
        }
        boolean newGallery = false;
        if (!imageDownloadQueue.containsKey(gallery)) {
            imageDownloadQueue.put(gallery, new LinkedList<>());
            newGallery = true;
        }
        //noinspection DataFlowIssue
        imageDownloadQueue.get(gallery).add(() -> {
            LogUtility.d("Requested url glide: " + url.get());
            RequestManager glide = GlideX.with(context);
            if (glide == null) return;
            Drawable logo = placeholder(view);
            RequestBuilder<Drawable> dra = glide.load(url.get());
            if (angle != 0)
                dra = dra.transform(new Rotate(angle));
            dra.error(logo)
                .addListener(new RequestListener<>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        if (gallery != null && !gallery.getGalleryData().getCheckedExt())
                            gallery.getGalleryData().setCheckedExt();
                        new Handler(context.getMainLooper()).post(() -> {
                            //noinspection DataFlowIssue
                            while (imageDownloadQueue.containsKey(gallery) && !imageDownloadQueue.get(gallery).isEmpty()) {
                                //noinspection DataFlowIssue
                                imageDownloadQueue.get(gallery).remove(0).run();
                            }
                        });
                        return false;
                    }
                })
                .placeholder(logo)
                .into(DebugImageMask.target(view));
        });
        if (newGallery) {
            //noinspection DataFlowIssue
            imageDownloadQueue.get(gallery).remove(0).run();
        } else if (priority) {
            //noinspection DataFlowIssue
            imageDownloadQueue.get(gallery).remove(imageDownloadQueue.get(gallery).size() - 1).run();
        } else if (gallery == null || gallery.getGalleryData().getCheckedExt()) {
            //noinspection DataFlowIssue
            while (!imageDownloadQueue.get(gallery).isEmpty())
                //noinspection DataFlowIssue
                imageDownloadQueue.get(gallery).remove(0).run();
        }
    }

    private static Uri getUrlForGallery(Gallery gallery, int page, boolean shouldFull) {
        return shouldFull ? gallery.getPageUrl(page) : gallery.getLowPage(page);
    }

    private static void loadLogo(ImageView imageView) {
        imageView.setImageDrawable(placeholder(imageView));
    }

    static Drawable placeholder(ImageView imageView) {
        Drawable logo = Global.getLogo(imageView.getResources());
        if (logo == null || imageView.getScaleType() != ImageView.ScaleType.CENTER_CROP) return logo;
        int width = Math.round(72 * imageView.getResources().getDisplayMetrics().density);
        int height = Math.round(width * (float) logo.getIntrinsicHeight() / logo.getIntrinsicWidth());
        // Unspecified intrinsic dimensions keep ImageView from cropping the placeholder.
        LayerDrawable placeholder = new LayerDrawable(new Drawable[]{logo}) {
            @Override public int getIntrinsicWidth() { return -1; }
            @Override public int getIntrinsicHeight() { return -1; }
        };
        placeholder.setLayerSize(0, width, height);
        placeholder.setLayerGravity(0, Gravity.CENTER);
        return placeholder;
    }

    public static void loadImage(Activity activity, Uri url, ImageView imageView) {
        loadImageOp(activity, imageView, null, () -> url, 0, false);
    }

    public static void loadImage(Activity activity, File file, ImageView imageView) {
        loadImage(activity, file == null ? null : Uri.fromFile(file), imageView);
    }

    /**
     * Load Resource using id
     */
    public static void loadImage(@DrawableRes int resource, ImageView imageView) {
        imageView.setImageResource(resource);
    }
}
