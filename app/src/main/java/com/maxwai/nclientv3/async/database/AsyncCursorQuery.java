// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import com.maxwai.nclientv3.utility.LogUtility;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns query cursors until replacement or disposal; only the latest request is published. */
public final class AsyncCursorQuery implements AutoCloseable {
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private volatile boolean closed;
    private Cursor active;

    public AsyncCursorQuery() {
        this(executor);
    }

    public AsyncCursorQuery(Executor worker) {
        this.worker = worker;
    }

    public void load(Supplier<Cursor> query, Consumer<Cursor> publish, Runnable failed) {
        if (closed) return;
        long request = generation.incrementAndGet();
        worker.execute(() -> {
            if (!current(request)) return;
            Cursor cursor = null;
            try {
                cursor = query.get();
                cursor.getCount();
            } catch (RuntimeException exception) {
                if (cursor != null) cursor.close();
                LogUtility.e("Unable to query list data", exception);
                main.post(() -> { if (current(request)) failed.run(); });
                return;
            }
            Cursor result = cursor;
            main.post(() -> {
                if (!current(request)) {
                    result.close();
                    return;
                }
                Cursor previous = active;
                active = result;
                try {
                    publish.accept(result);
                } finally {
                    if (previous != null) previous.close();
                }
            });
        });
    }

    private boolean current(long request) {
        return !closed && generation.get() == request;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        if (active != null) {
            active.close();
            active = null;
        }
    }
}
