// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Computes bounded, visible source regions for continuous-reader image tiles. */
final class ContinuousTileGrid {
    private static final int MAX_TILES = 256;
    private static final int MAX_SAMPLE = 1 << 20;

    private final int width;
    private final int height;
    private final long span;

    ContinuousTileGrid(int width, int height, int tilePixels, int sample) {
        if (width <= 0 || height <= 0 || tilePixels <= 0
            || tilePixels > ContinuousTileDecoder.MAX_TILE_DIMENSION || sample <= 0
            || sample > MAX_SAMPLE || (sample & (sample - 1)) != 0) {
            throw new IllegalArgumentException("Invalid tile grid dimensions or sampling level");
        }
        this.width = width;
        this.height = height;
        this.span = (long) tilePixels * sample;
    }

    static int sampleForScale(float screenPixelsPerSourcePixel) {
        if (!Float.isFinite(screenPixelsPerSourcePixel) || screenPixelsPerSourcePixel <= 0f) return 1;
        int sample = 1;
        while (sample < MAX_SAMPLE && screenPixelsPerSourcePixel * (sample * 2L) <= 1f) sample *= 2;
        return sample;
    }

    @NonNull
    List<Rect> visibleTiles(@NonNull Rect visible, int limit) {
        int boundedLimit = Math.max(0, Math.min(MAX_TILES, limit));
        int left = Math.max(0, visible.left);
        int top = Math.max(0, visible.top);
        int right = Math.min(width, visible.right);
        int bottom = Math.min(height, visible.bottom);
        if (boundedLimit == 0 || left >= right || top >= bottom) return Collections.emptyList();

        long minColumn = left / span;
        long maxColumn = (right - 1L) / span;
        long minRow = top / span;
        long maxRow = (bottom - 1L) / span;
        long centerColumn = ((long) left + (right - left) / 2L) / span;
        long centerRow = ((long) top + (bottom - top) / 2L) / span;
        List<Rect> tiles = new ArrayList<>(boundedLimit);
        for (int radius = 0; tiles.size() < boundedLimit; radius++) {
            long startRow = Math.max(minRow, centerRow - radius);
            long endRow = Math.min(maxRow, centerRow + radius);
            long startColumn = Math.max(minColumn, centerColumn - radius);
            long endColumn = Math.min(maxColumn, centerColumn + radius);
            for (long row = startRow; row <= endRow && tiles.size() < boundedLimit; row++) {
                for (long column = startColumn; column <= endColumn && tiles.size() < boundedLimit; column++) {
                    if (Math.max(Math.abs(row - centerRow), Math.abs(column - centerColumn)) != radius) continue;
                    long tileLeft = column * span;
                    long tileTop = row * span;
                    tiles.add(new Rect((int) tileLeft, (int) tileTop,
                        (int) Math.min(width, tileLeft + span), (int) Math.min(height, tileTop + span)));
                }
            }
            if (startRow == minRow && endRow == maxRow && startColumn == minColumn && endColumn == maxColumn) break;
        }
        return tiles;
    }
}
