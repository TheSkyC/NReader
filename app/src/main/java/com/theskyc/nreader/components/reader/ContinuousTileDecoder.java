// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/** Owns a region decoder. Open and decode on a background thread. */
final class ContinuousTileDecoder implements ContinuousTileLoader.Source {
    static final int MAX_TILE_DIMENSION = 512;
    private static final int MAX_SAMPLE = 1 << 20;
    static final int MAX_TILE_BYTES = 2 * 1024 * 1024;

    private final BitmapRegionDecoder decoder;
    private final int width;
    private final int height;
    private boolean closed;

    private ContinuousTileDecoder(@NonNull BitmapRegionDecoder decoder) {
        this.decoder = decoder;
        width = decoder.getWidth();
        height = decoder.getHeight();
    }

    @NonNull
    static ContinuousTileDecoder open(@NonNull File file) throws IOException {
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
        if (decoder == null) throw new IOException("Could not open image region decoder");
        if (decoder.getWidth() <= 0 || decoder.getHeight() <= 0) {
            decoder.recycle();
            throw new IOException("Invalid image dimensions");
        }
        return new ContinuousTileDecoder(decoder);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @NonNull
    public synchronized Bitmap decode(@NonNull Rect region, int sample) throws IOException {
        if (closed) throw new IllegalStateException("Image region decoder is closed");
        Rect requested = new Rect(region);
        long regionWidth = (long) requested.right - requested.left;
        long regionHeight = (long) requested.bottom - requested.top;
        if (requested.left < 0 || requested.top < 0 || requested.right > width || requested.bottom > height
            || regionWidth <= 0 || regionHeight <= 0 || sample <= 0 || sample > MAX_SAMPLE
            || (sample & (sample - 1)) != 0
            || (regionWidth + sample - 1L) / sample > MAX_TILE_DIMENSION
            || (regionHeight + sample - 1L) / sample > MAX_TILE_DIMENSION) {
            throw new IllegalArgumentException("Invalid image tile request");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = decoder.decodeRegion(requested, options);
        if (bitmap == null) throw new IOException("Could not decode image tile");
        if (bitmap.getWidth() > MAX_TILE_DIMENSION || bitmap.getHeight() > MAX_TILE_DIMENSION
            || bitmap.getAllocationByteCount() > MAX_TILE_BYTES) {
            bitmap.recycle();
            throw new IOException("Decoded image tile exceeds size limit");
        }
        return bitmap;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        decoder.recycle();
    }
}
