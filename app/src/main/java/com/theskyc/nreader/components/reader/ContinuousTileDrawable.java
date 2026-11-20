// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Draws bounded source tiles over an existing low-resolution image. */
final class ContinuousTileDrawable extends Drawable implements Drawable.Callback, AutoCloseable {
    private static final int TILE_PIXELS = ContinuousTileDecoder.MAX_TILE_DIMENSION - 2;
    private static final int MAX_SAMPLE = 1 << 20;
    private final Drawable fallback;
    private final int sourceWidth;
    private final int sourceHeight;
    private final int visibleTileLimit;
    private final ContinuousTileLoader loader;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path readyTilesPath = new Path();
    private final RectF readyBounds = new RectF();
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private boolean closed;
    @Nullable private final Supplier<Rect> visibleBounds;

    ContinuousTileDrawable(@NonNull Drawable fallback, int sourceWidth, int sourceHeight, int visibleTileLimit,
                           @NonNull ContinuousTileLoader.Source source, @NonNull ContinuousTileCache cache,
                           @NonNull Executor worker, @NonNull Executor ui, @NonNull Consumer<Exception> onFailure) {
        this(fallback, sourceWidth, sourceHeight, visibleTileLimit, source, cache, worker, ui, onFailure, null);
    }

    ContinuousTileDrawable(@NonNull Drawable fallback, int sourceWidth, int sourceHeight, int visibleTileLimit,
                           @NonNull ContinuousTileLoader.Source source, @NonNull ContinuousTileCache cache,
                           @NonNull Executor worker, @NonNull Executor ui, @NonNull Consumer<Exception> onFailure,
                           @Nullable Supplier<Rect> visibleBounds) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || visibleTileLimit <= 0 || visibleTileLimit >= 256
            || !cache.supportsDecoderTiles())
            throw new IllegalArgumentException("Invalid image tile dimensions or visible limit");
        this.fallback = fallback.mutate();
        this.fallback.setCallback(this);
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.visibleTileLimit = visibleTileLimit;
        this.visibleBounds = visibleBounds;
        loader = new ContinuousTileLoader(source, cache, worker, ui, this::invalidateSelf, onFailure);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (closed) {
            fallback.draw(canvas);
            return;
        }
        Rect bounds = getBounds();
        Rect canvasClip = new Rect();
        boolean hasClip = canvas.getClipBounds(canvasClip);
        Rect clip = new Rect(canvasClip);
        if (bounds.isEmpty() || !hasClip || !clip.intersect(bounds)
            || (visibleBounds != null && !clip.intersect(visibleBounds.get()))) {
            loader.cancelPending();
            fallback.draw(canvas);
            return;
        }
        int tileLimit = Math.min(visibleTileLimit, loader.capacity());
        if (tileLimit == 0) {
            loader.cancelPending();
            fallback.draw(canvas);
            return;
        }
        int sample = ContinuousTileGrid.sampleForScale(screenScale(canvas, bounds));
        List<Rect> tiles;
        int gridWidth;
        int gridHeight;
        while (true) {
            // Keep every region on the same pixel grid as a whole-image sampled decode.
            gridWidth = Math.max(1, sourceWidth / sample) * sample;
            gridHeight = Math.max(1, sourceHeight / sample) * sample;
            Rect visible = new Rect(
                sourceCoordinate(clip.left - bounds.left, bounds.width(), gridWidth, false),
                sourceCoordinate(clip.top - bounds.top, bounds.height(), gridHeight, false),
                sourceCoordinate(clip.right - bounds.left, bounds.width(), gridWidth, true),
                sourceCoordinate(clip.bottom - bounds.top, bounds.height(), gridHeight, true));
            ContinuousTileGrid grid = new ContinuousTileGrid(gridWidth, gridHeight, TILE_PIXELS, sample);
            tiles = grid.visibleTiles(visible, tileLimit + 1);
            if (tiles.size() <= tileLimit || sample >= MAX_SAMPLE) break;
            sample *= 2;
        }
        Rect missing = null;
        List<Bitmap> bitmaps = new ArrayList<>(visibleTileLimit);
        List<RectF> destinations = new ArrayList<>(visibleTileLimit);
        List<RectF> expandedDestinations = new ArrayList<>(visibleTileLimit);
        int count = Math.min(tiles.size(), tileLimit);
        for (int index = 0; index < count; index++) {
            Rect tile = tiles.get(index);
            Rect expanded = new Rect((int) Math.max(0L, (long) tile.left - sample),
                (int) Math.max(0L, (long) tile.top - sample),
                (int) Math.min(sourceWidth, (long) tile.right + sample),
                (int) Math.min(sourceHeight, (long) tile.bottom + sample));
            Bitmap bitmap = loader.get(expanded, sample);
            if (bitmap == null) {
                if (missing == null) missing = expanded;
                continue;
            }
            bitmaps.add(bitmap);
            RectF destination = destination(tile, bounds, gridWidth, gridHeight);
            // Outer bitmap edges supply their own coverage; exclude the fallback's edge fringe too.
            if (tile.left == 0) destination.left = Math.min(destination.left, canvasClip.left - 1f);
            if (tile.top == 0) destination.top = Math.min(destination.top, canvasClip.top - 1f);
            if (tile.right == gridWidth) destination.right = Math.max(destination.right, canvasClip.right + 1f);
            if (tile.bottom == gridHeight) destination.bottom = Math.max(destination.bottom, canvasClip.bottom + 1f);
            destinations.add(destination);
            Rect decoded = new Rect(expanded.left, expanded.top, expanded.left + bitmap.getWidth() * sample,
                expanded.top + bitmap.getHeight() * sample);
            expandedDestinations.add(destination(decoded, bounds, gridWidth, gridHeight));
        }
        int saved = canvas.save();
        // Exclude adjacent tiles together so their shared edges do not reveal the preview.
        readyTilesPath.rewind();
        for (RectF destination : destinations) readyTilesPath.addRect(destination, Path.Direction.CW);
        if (!readyTilesPath.isEmpty()) {
            readyTilesPath.op(readyTilesPath, Path.Op.UNION);
            if (readyTilesPath.isRect(readyBounds)) canvas.clipOutRect(readyBounds);
            else canvas.clipOutPath(readyTilesPath);
        }
        fallback.draw(canvas);
        canvas.restoreToCount(saved);
        for (int index = 0; index < bitmaps.size(); index++) {
            int tileSave = canvas.save();
            canvas.clipRect(destinations.get(index));
            canvas.drawBitmap(bitmaps.get(index), null, expandedDestinations.get(index), paint);
            canvas.restoreToCount(tileSave);
        }
        if (missing != null) loader.request(missing, sample);
        else loader.cancelPending();
    }

    @NonNull
    private RectF destination(@NonNull Rect tile, @NonNull Rect bounds, int gridWidth, int gridHeight) {
        return new RectF(
            bounds.left + (float) ((double) tile.left * bounds.width() / gridWidth),
            bounds.top + (float) ((double) tile.top * bounds.height() / gridHeight),
            bounds.left + (float) ((double) tile.right * bounds.width() / gridWidth),
            bounds.top + (float) ((double) tile.bottom * bounds.height() / gridHeight));
    }

    private static int sourceCoordinate(int coordinate, int extent, int sourceExtent, boolean roundUp) {
        double value = (double) coordinate * sourceExtent / extent;
        return (int) Math.max(0, Math.min(sourceExtent, roundUp ? Math.ceil(value) : Math.floor(value)));
    }

    @SuppressWarnings("deprecation")
    private float screenScale(@NonNull Canvas canvas, @NonNull Rect bounds) {
        canvas.getMatrix(matrix);
        matrix.getValues(matrixValues);
        double horizontal = Math.hypot(matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSKEW_Y]);
        double vertical = Math.hypot(matrixValues[Matrix.MSKEW_X], matrixValues[Matrix.MSCALE_Y]);
        return (float) Math.max(horizontal * bounds.width() / sourceWidth,
            vertical * bounds.height() / sourceHeight);
    }

    @Override protected void onBoundsChange(Rect bounds) { fallback.setBounds(bounds); }
    @Override public int getIntrinsicWidth() { return sourceWidth; }
    @Override public int getIntrinsicHeight() { return sourceHeight; }
    @Override public void setAlpha(int alpha) { fallback.setAlpha(alpha); paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { fallback.setColorFilter(filter); paint.setColorFilter(filter); invalidateSelf(); }
    @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void invalidateDrawable(@NonNull Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) { unscheduleSelf(what); }

    @NonNull Drawable fallback() { return fallback; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        fallback.setCallback(null);
        loader.close();
    }
}
