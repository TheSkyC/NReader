// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.widget.ImageView;

import androidx.annotation.NonNull;

public final class ReaderImageFilter {
    public static final int NONE = 0;
    public static final int GRAYSCALE = 1;
    public static final int INVERT = 2;

    private ReaderImageFilter() {
    }

    public static int sanitize(int mode) {
        return mode >= NONE && mode <= INVERT ? mode : NONE;
    }

    public static void apply(@NonNull ImageView image, int mode) {
        switch (sanitize(mode)) {
            case GRAYSCALE:
                ColorMatrix gray = new ColorMatrix();
                gray.setSaturation(0f);
                image.setColorFilter(new ColorMatrixColorFilter(gray));
                break;
            case INVERT:
                image.setColorFilter(new ColorMatrixColorFilter(new float[]{
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                }));
                break;
            default:
                image.clearColorFilter();
                break;
        }
    }
}
