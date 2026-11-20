// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.widget.ImageView;

import androidx.annotation.NonNull;

public final class ReaderImageFilter {
    public static final int NONE = 0;
    public static final int GRAYSCALE = 1;
    public static final int INVERT = 2;
    public static final int SEPIA = 3;

    private ReaderImageFilter() {
    }

    public static int sanitize(int mode) {
        return mode >= NONE && mode <= SEPIA ? mode : NONE;
    }

    public static void apply(@NonNull ImageView image, int mode) {
        apply(image, mode, 0, 0, 0);
    }

    public static void apply(@NonNull ImageView image, int mode, int brightness,
                             int contrast, int saturation) {
        int safeBrightness = sanitizeAdjustment(brightness);
        int safeContrast = sanitizeAdjustment(contrast);
        int safeSaturation = sanitizeAdjustment(saturation);
        int safeMode = sanitize(mode);
        if (safeMode == NONE && safeBrightness == 0 && safeContrast == 0 && safeSaturation == 0) {
            image.clearColorFilter();
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        if (safeMode == GRAYSCALE) {
            matrix.setSaturation(0f);
        } else if (safeMode == INVERT) {
            matrix.set(new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            });
        } else if (safeMode == SEPIA) {
            matrix.set(new float[]{
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            });
        }
        ColorMatrix saturationMatrix = new ColorMatrix();
        saturationMatrix.setSaturation(1f + safeSaturation / 100f);
        matrix.postConcat(saturationMatrix);
        float contrastScale = (safeContrast + 100f) / 100f;
        float brightnessOffset = safeBrightness * 2.55f;
        matrix.postConcat(new ColorMatrix(new float[]{
            contrastScale, 0f, 0f, 0f, brightnessOffset,
            0f, contrastScale, 0f, 0f, brightnessOffset,
            0f, 0f, contrastScale, 0f, brightnessOffset,
            0f, 0f, 0f, 1f, 0f
        }));
        image.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    public static int sanitizeAdjustment(int value) {
        return Math.max(-100, Math.min(100, value));
    }
}
