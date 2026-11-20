// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Coalesces one image session's tile requests and disposes its source off the UI thread. */
final class ContinuousTileLoader implements AutoCloseable {
    interface Source extends AutoCloseable {
        @NonNull Bitmap decode(@NonNull Rect region, int sample) throws IOException;
        @Override void close();
    }

    private final Source source;
    private final ContinuousTileCache cache;
    private final ContinuousTileCache.Session session;
    private final Executor worker;
    private final Executor ui;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable budgetListener = () -> mainHandler.post(this::postBudgetChanged);
    @Nullable private Runnable onTile;
    @Nullable private Consumer<Exception> onFailure;
    @Nullable private Request desired;
    private boolean workerRunning;
    private boolean closed;
    private long generation;

    ContinuousTileLoader(@NonNull Source source, @NonNull ContinuousTileCache cache,
                         @NonNull Executor worker, @NonNull Executor ui,
                         @NonNull Runnable onTile, @NonNull Consumer<Exception> onFailure) {
        this.source = source;
        this.cache = cache;
        this.worker = worker;
        this.ui = ui;
        this.onTile = onTile;
        this.onFailure = onFailure;
        this.session = cache.newSession(budgetListener);
    }

    @Nullable
    synchronized Bitmap get(@NonNull Rect region, int sample) {
        return closed ? null : cache.get(session, region, sample);
    }

    synchronized int capacity() {
        return closed ? 0 : cache.capacity(session);
    }

    synchronized void request(@NonNull Rect region, int sample) {
        if (closed) return;
        Request next = new Request(region, sample);
        if (next.equals(desired)) return;
        generation++;
        if (cache.get(session, next.region, sample) != null) {
            desired = null;
            return;
        }
        desired = next;
        startWorker();
    }

    synchronized void cancelPending() {
        if (closed || desired == null) return;
        desired = null;
        generation++;
    }

    private void startWorker() {
        if (workerRunning) return;
        workerRunning = true;
        try {
            worker.execute(this::runWorker);
        } catch (RejectedExecutionException exception) {
            workerRunning = false;
            boolean report = !closed;
            closed = true;
            desired = null;
            cache.removeSession(session);
            source.close();
            if (report) {
                long failedGeneration = generation;
                postToUi(() -> reportFailure(failedGeneration, exception));
            }
        }
    }

    private void runWorker() {
        while (true) {
            Request current;
            synchronized (this) {
                if (closed) {
                    workerRunning = false;
                    current = null;
                } else {
                    current = desired;
                    if (current == null) {
                        workerRunning = false;
                        return;
                    }
                }
            }
            if (current == null) {
                source.close();
                return;
            }

            Bitmap bitmap = null;
            Exception failure = null;
            boolean memoryFailure = false;
            try {
                bitmap = source.decode(current.region, current.sample);
            } catch (IOException | RuntimeException exception) {
                failure = exception;
            } catch (OutOfMemoryError error) {
                cache.clear();
                memoryFailure = true;
                failure = new IOException("Insufficient memory to decode image tile", error);
            }
            synchronized (this) {
                if (closed || (!memoryFailure && !current.equals(desired))) {
                    if (bitmap != null) bitmap.recycle();
                    continue;
                }
                desired = null;
                long completedGeneration = generation;
                ContinuousTileCache.StoreResult stored = failure == null && bitmap != null
                    ? cache.put(session, current.region, current.sample, bitmap) : ContinuousTileCache.StoreResult.INVALID;
                if (stored == ContinuousTileCache.StoreResult.NO_CAPACITY) {
                    bitmap.recycle();
                } else if (stored == ContinuousTileCache.StoreResult.STORED) {
                    postToUi(() -> reportTile(completedGeneration));
                } else {
                    if (bitmap != null) bitmap.recycle();
                    closed = true;
                    cache.removeSession(session);
                    Exception result = failure != null ? failure : new IOException("Could not retain image tile");
                    postToUi(() -> reportFailure(completedGeneration, result));
                }
            }
        }
    }

    private void postToUi(@NonNull Runnable action) {
        try {
            ui.execute(action);
        } catch (RejectedExecutionException exception) {
            closed = true;
            desired = null;
            cache.removeSession(session);
            onTile = null;
            onFailure = null;
        }
    }

    private synchronized void reportTile(long completedGeneration) {
        if (!closed && generation == completedGeneration && onTile != null) onTile.run();
    }

    private void postBudgetChanged() {
        try {
            ui.execute(this::reportBudgetChanged);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private synchronized void reportBudgetChanged() {
        if (!closed && onTile != null) onTile.run();
    }

    private synchronized void reportFailure(long completedGeneration, @NonNull Exception exception) {
        if (closed && generation == completedGeneration && onFailure != null) {
            Consumer<Exception> callback = onFailure;
            onFailure = null;
            onTile = null;
            callback.accept(exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            generation++;
            onTile = null;
            onFailure = null;
            return;
        }
        closed = true;
        generation++;
        desired = null;
        cache.removeSession(session);
        onTile = null;
        onFailure = null;
        startWorker();
    }

    private static final class Request {
        final Rect region;
        final int sample;

        Request(Rect region, int sample) {
            this.region = new Rect(region);
            this.sample = sample;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Request)) return false;
            Request request = (Request) other;
            return sample == request.sample && region.equals(request.region);
        }

        @Override public int hashCode() {
            return 31 * region.hashCode() + sample;
        }
    }
}
