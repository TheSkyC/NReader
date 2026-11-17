// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Crops opaque near-white margins for display while leaving uncertain images unchanged. */
public final class ReaderAutoCropTransformation extends BitmapTransformation {
    private static final int MAX_SCAN_PIXELS = 2_000_000;
    private static final int WHITE_THRESHOLD = 248;
    private static final int OPAQUE_THRESHOLD = 250;
    private static final int MIN_MARGIN_PX = 2;
    private static final byte[] ID = "nreader-auto-crop-v1".getBytes(StandardCharsets.UTF_8);

    @Nullable
    public static Rect findContentBounds(@NonNull Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 8 || height < 8) return null;
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt(
            ((double) width * height) / MAX_SCAN_PIXELS)));
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        int[] pixel = new int[1];
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                bitmap.getPixels(pixel, 0, 1, x, y, 1, 1);
                int color = pixel[0];
                if (Color.alpha(color) < OPAQUE_THRESHOLD) return null;
                if (!isWhite(color)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) return null;
        int padding = stride;
        left = Math.max(0, left - padding);
        top = Math.max(0, top - padding);
        right = Math.min(width - 1, right + padding);
        bottom = Math.min(height - 1, bottom + padding);
        if (left < MIN_MARGIN_PX && top < MIN_MARGIN_PX
            && width - right - 1 < MIN_MARGIN_PX && height - bottom - 1 < MIN_MARGIN_PX) return null;
        if (right - left + 1 < width / 2 || bottom - top + 1 < height / 2) return null;
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private static boolean isWhite(int color) {
        return Color.red(color) >= WHITE_THRESHOLD
            && Color.green(color) >= WHITE_THRESHOLD
            && Color.blue(color) >= WHITE_THRESHOLD;
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        Rect bounds = findContentBounds(toTransform);
        if (bounds == null || bounds.left == 0 && bounds.top == 0
            && bounds.right == toTransform.getWidth() && bounds.bottom == toTransform.getHeight()) {
            return toTransform;
        }
        Bitmap.Config config = toTransform.getConfig();
        if (config == null || config == Bitmap.Config.HARDWARE) config = Bitmap.Config.ARGB_8888;
        Bitmap cropped = pool.get(bounds.width(), bounds.height(), config);
        Canvas canvas = new Canvas(cropped);
        canvas.drawBitmap(toTransform, -bounds.left, -bounds.top, null);
        return cropped;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID);
    }
}
