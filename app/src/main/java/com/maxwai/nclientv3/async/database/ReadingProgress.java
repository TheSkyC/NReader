// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.io.IOException;

public final class ReadingProgress {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Object storageLock = new Object();
    private static final AtomicLong generation = new AtomicLong();

    private ReadingProgress() {}

    public static void save(int id, ReadingPosition position) {
        if (id <= 0) return;
        final long scheduledGeneration = generation.get();
        executor.execute(() -> {
            synchronized (storageLock) {
                if (scheduledGeneration != generation.get()) return;
                try {
                    Queries.ReadingProgressTable.insert(id, position);
                } catch (RuntimeException exception) {
                    LogUtility.e("Unable to save reading position", exception);
                }
            }
        });
    }

    public static void load(int id, Consumer<ReadingPosition> result) {
        executor.execute(() -> {
            ReadingPosition position = null;
            try {
                synchronized (storageLock) {
                    if (id > 0) position = Queries.ReadingProgressTable.fromId(id);
                }
            } catch (RuntimeException exception) {
                LogUtility.e("Unable to load reading position", exception);
            }
            result.accept(position);
        });
    }

    public static void restore(RestoreAction action) throws IOException {
        synchronized (storageLock) {
            generation.incrementAndGet();
            try {
                action.run();
            } finally {
                generation.incrementAndGet();
            }
        }
    }

    public interface RestoreAction {
        void run() throws IOException;
    }
}
