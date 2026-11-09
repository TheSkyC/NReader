// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.maxwai.nclientv3.BuildConfig;

public final class DebugImageMask {
    private static final String PREFERENCES = "DebugDisplay";
    private static final String ENABLED = "mask_images";

    private DebugImageMask() {}

    public static boolean isEnabled(Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences(PREFERENCES, 0).getBoolean(ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (BuildConfig.DEBUG) context.getSharedPreferences(PREFERENCES, 0).edit().putBoolean(ENABLED, enabled).apply();
    }

    @Nullable
    public static Drawable wrap(Context context, @Nullable Drawable drawable) {
        if (drawable == null || !isEnabled(context)) return drawable;
        return new Mask(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    }

    public static DrawableImageViewTarget target(ImageView view) {
        return new DrawableImageViewTarget(view) {
            @Override
            protected void setResource(@Nullable Drawable resource) {
                super.setResource(wrap(view.getContext(), resource));
            }
        };
    }

    private static final class Mask extends Drawable {
        private final int width;
        private final int height;
        private final Paint paint = new Paint();

        Mask(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            int tile = Math.max(1, Math.max(bounds.width(), bounds.height()) / 12);
            paint.setColor(0xff343a40);
            canvas.drawRect(bounds, paint);
            paint.setColor(0xff59636e);
            for (int row = 0, top = bounds.top; top < bounds.bottom; row++, top += tile) {
                for (int left = bounds.left + (row % 2) * tile; left < bounds.right; left += tile * 2) {
                    canvas.drawRect(left, top, Math.min(left + tile, bounds.right), Math.min(top + tile, bounds.bottom), paint);
                }
            }
        }

        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
