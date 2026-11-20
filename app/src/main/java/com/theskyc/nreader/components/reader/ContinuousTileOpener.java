// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.MainThread;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Serializes a bounded number of decoder openings until delivery finishes.
 * Request, cancel, close and UI delivery must be called on the UI thread. */
final class ContinuousTileOpener<T extends AutoCloseable> implements AutoCloseable {
    private static final int MAX_PENDING = 16;
    interface Factory<T> {
        @Nullable T open() throws IOException;
    }
    interface CancellableFactory<T> {
        @Nullable T open(@NonNull BooleanSupplier canceled) throws IOException;
    }

    private final Executor worker;
    private final Executor ui;
    private final ArrayDeque<Request<T>> pending = new ArrayDeque<>();
    @Nullable private Request<T> current;
    private boolean busy;
    private boolean closed;

    ContinuousTileOpener(@NonNull Executor worker, @NonNull Executor ui) {
        this.worker = worker;
        this.ui = ui;
    }

    @NonNull
    @MainThread
    synchronized Request<T> request(@NonNull Factory<T> factory, @NonNull Consumer<T> onReady,
                                    @NonNull Consumer<Exception> onFailure) {
        return requestCancellable(canceled -> factory.open(), onReady, onFailure);
    }

    @NonNull
    @MainThread
    synchronized Request<T> requestCancellable(@NonNull CancellableFactory<T> factory, @NonNull Consumer<T> onReady,
                                               @NonNull Consumer<Exception> onFailure) {
        Request<T> request = new Request<>(factory, onReady, onFailure);
        if (closed) {
            request.canceled = true;
            return request;
        }
        if (pending.size() >= MAX_PENDING) {
            try {
                ui.execute(() -> reportRejected(request));
            } catch (RejectedExecutionException ignored) {
                request.canceled = true;
            }
            return request;
        }
        pending.addLast(request);
        startNext();
        return request;
    }

    @MainThread
    synchronized void cancel(@NonNull Request<T> request) {
        request.canceled = true;
        pending.remove(request);
    }

    private void startNext() {
        if (closed || busy || pending.isEmpty()) return;
        current = pending.removeFirst();
        busy = true;
        Request<T> request = current;
        try {
            worker.execute(() -> open(request));
        } catch (RejectedExecutionException exception) {
            try {
                ui.execute(() -> deliver(request, null, exception));
            } catch (RejectedExecutionException ignored) {
                finish(request);
            }
        }
    }

    private void open(@NonNull Request<T> request) {
        synchronized (this) {
            if (closed || request.canceled) {
                finish(request);
                return;
            }
        }
        T result = null;
        Exception failure = null;
        try {
            result = request.factory.open(() -> request.canceled);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
        } catch (OutOfMemoryError error) {
            failure = new IOException("Insufficient memory to open image tiles", error);
        }
        boolean canceled;
        synchronized (this) {
            canceled = closed || request.canceled;
        }
        if (canceled) {
            dispose(result);
            synchronized (this) { finish(request); }
            return;
        }
        T opened = result;
        Exception error = failure;
        try {
            ui.execute(() -> deliver(request, opened, error));
        } catch (RejectedExecutionException exception) {
            dispose(opened);
            synchronized (this) { finish(request); }
        }
    }

    private void deliver(@NonNull Request<T> request, @Nullable T result, @Nullable Exception failure) {
        boolean accepted;
        synchronized (this) {
            accepted = !closed && !request.canceled && current == request;
            if (!accepted && result != null) {
                try {
                    worker.execute(() -> dispose(result));
                } catch (RejectedExecutionException exception) {
                    dispose(result);
                }
            }
            finish(request);
        }
        if (!accepted) return;
        if (failure != null) request.onFailure.accept(failure);
        else request.onReady.accept(result);
    }

    private synchronized void reportRejected(@NonNull Request<T> request) {
        if (!closed && !request.canceled) {
            request.canceled = true;
            request.onFailure.accept(new RejectedExecutionException("Image tile opening queue is full"));
        }
    }

    private void finish(@NonNull Request<T> request) {
        if (current != request) return;
        current = null;
        busy = false;
        startNext();
    }

    private void dispose(@Nullable T result) {
        if (result == null) return;
        try {
            result.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    @MainThread
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Request<T> request : pending) request.canceled = true;
        if (current != null) current.canceled = true;
        pending.clear();
    }

    static final class Request<T> {
        final CancellableFactory<T> factory;
        final Consumer<T> onReady;
        final Consumer<Exception> onFailure;
        volatile boolean canceled;

        Request(CancellableFactory<T> factory, Consumer<T> onReady, Consumer<Exception> onFailure) {
            this.factory = factory;
            this.onReady = onReady;
            this.onFailure = onFailure;
        }
    }
}
