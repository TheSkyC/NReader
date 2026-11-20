// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Holds the same snapshot until both the fallback request and decoder release it. */
final class ContinuousTileSnapshotSession implements AutoCloseable {
    private final ContinuousTileSnapshotStore.Lease lease;
    private final ContinuousTileLoader.Source decoder;
    private final int width;
    private final int height;
    private final Consumer<IOException> onCleanupFailure;
    private boolean sourceTaken;
    private boolean sourceClosed;
    private boolean fallbackReleased;
    private boolean leaseClosed;
    private int openFallbackStreams;

    ContinuousTileSnapshotSession(ContinuousTileSnapshotStore.Lease lease, ContinuousTileLoader.Source decoder,
                                  int width, int height, Consumer<IOException> onCleanupFailure) {
        this.lease = lease;
        this.decoder = decoder;
        this.width = width;
        this.height = height;
        this.onCleanupFailure = onCleanupFailure;
    }

    @Nullable
    static ContinuousTileSnapshotSession open(@NonNull ContinuousTileSnapshotStore store, @NonNull File source,
                                               @NonNull BooleanSupplier canceled, int minimumHeight,
                                               @NonNull Consumer<IOException> onCleanupFailure) throws IOException {
        checkCanceled(canceled);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        checkCanceled(canceled);
        if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth <= 0
            || bounds.outHeight <= minimumHeight || bounds.outHeight / (double) bounds.outWidth < 4) return null;
        ContinuousTileSnapshotStore.Lease lease = store.create(source, canceled);
        ContinuousTileDecoder decoder = null;
        try {
            checkCanceled(canceled);
            if (!ContinuousTileSourceEligibility.isEligible(lease.file(), canceled)) {
                checkCanceled(canceled);
                lease.close();
                return null;
            }
            checkCanceled(canceled);
            decoder = ContinuousTileDecoder.open(lease.file());
            checkCanceled(canceled);
            if (decoder.height() <= minimumHeight || decoder.height() / (double) decoder.width() < 4) {
                decoder.close();
                lease.close();
                return null;
            }
            return new ContinuousTileSnapshotSession(lease, decoder, decoder.width(), decoder.height(), onCleanupFailure);
        } catch (IOException | RuntimeException | Error failure) {
            if (decoder != null) decoder.close();
            try {
                lease.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void checkCanceled(BooleanSupplier canceled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || canceled.getAsBoolean())
            throw new InterruptedIOException("Image snapshot session interrupted");
    }

    @NonNull synchronized File file() {
        if (fallbackReleased) throw new IllegalStateException("Image fallback is released");
        return lease.file();
    }

    @NonNull synchronized InputStream openFallbackStream() throws IOException {
        if (fallbackReleased) throw new IOException("Image fallback is released");
        InputStream input = new FileInputStream(lease.file());
        openFallbackStreams++;
        return new FilterInputStream(input) {
            private boolean closed;

            @Override public synchronized void close() throws IOException {
                if (closed) {
                    synchronized (ContinuousTileSnapshotSession.this) {
                        releaseLeaseIfUnused();
                    }
                    return;
                }
                super.close();
                closed = true;
                synchronized (ContinuousTileSnapshotSession.this) {
                    openFallbackStreams--;
                    releaseLeaseIfUnused();
                }
            }
        };
    }

    int width() { return width; }
    int height() { return height; }

    @NonNull synchronized ContinuousTileLoader.Source takeSource() {
        if (sourceTaken || fallbackReleased) throw new IllegalStateException("Image tile source is unavailable");
        sourceTaken = true;
        return new ContinuousTileLoader.Source() {
            @Override public Bitmap decode(@NonNull Rect region, int sample) throws IOException {
                return decoder.decode(region, sample);
            }

            @Override public void close() {
                synchronized (ContinuousTileSnapshotSession.this) {
                    if (!sourceClosed) {
                        decoder.close();
                        sourceClosed = true;
                    }
                    try {
                        releaseLeaseIfUnused();
                    } catch (IOException exception) {
                        onCleanupFailure.accept(exception);
                    }
                }
            }
        };
    }

    /** Called after the fallback request has been cleared; may perform disk I/O. */
    @Override public synchronized void close() throws IOException {
        fallbackReleased = true;
        if (!sourceTaken && !sourceClosed) {
            decoder.close();
            sourceClosed = true;
        }
        releaseLeaseIfUnused();
    }

    private void releaseLeaseIfUnused() throws IOException {
        if (fallbackReleased && sourceClosed && openFallbackStreams == 0 && !leaseClosed) {
            lease.close();
            leaseClosed = true;
        }
    }
}
