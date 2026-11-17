// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

public final class ReaderScaleMode {
    public static final int SMART = 0;
    public static final int FIT_SCREEN = 1;
    public static final int FIT_WIDTH = 2;
    public static final int ORIGINAL = 3;

    private ReaderScaleMode() {
    }

    public static int sanitize(int mode) {
        return mode >= SMART && mode <= ORIGINAL ? mode : SMART;
    }

    public static float calculate(int mode, int drawableWidth, int drawableHeight,
                                  int viewWidth, int viewHeight, int deviceWidth, int deviceHeight,
                                  float smartZoom, float maxScale) {
        if (drawableWidth <= 0 || drawableHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f;
        float fitScale = Math.min((float) viewWidth / drawableWidth, (float) viewHeight / drawableHeight);
        if (fitScale <= 0f) return 1f;
        float scale;
        switch (sanitize(mode)) {
            case FIT_SCREEN:
                scale = 1f;
                break;
            case FIT_WIDTH:
                scale = ((float) viewWidth / drawableWidth) / fitScale;
                break;
            case ORIGINAL:
                scale = 1f / fitScale;
                break;
            case SMART:
            default:
                if (drawableHeight < drawableWidth * 2L) return clamp(smartZoom, maxScale);
                float deviceRatio = deviceWidth > 0 && deviceHeight > 0
                    ? ((float) deviceWidth * drawableHeight) / ((float) deviceHeight * drawableWidth)
                    : 1f;
                scale = Math.max(deviceRatio, smartZoom);
                scale = (float) Math.floor(scale);
                break;
        }
        return clamp(scale, maxScale);
    }

    private static float clamp(float scale, float maxScale) {
        float upper = Math.max(1f, maxScale);
        return Math.max(1f, Math.min(upper, scale));
    }
}
