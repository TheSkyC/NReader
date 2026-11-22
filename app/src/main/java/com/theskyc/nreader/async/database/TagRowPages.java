package com.theskyc.nreader.async.database;

import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.utility.LogUtility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Keeps the tag list's live SQLite cursor on a worker and publishes only copied rows. */
@MainThread
public final class TagRowPages implements AutoCloseable {
    private static final int PAGE_SIZE = 64;
    private static final int CACHED_PAGES = 8;
    private static final int NO_PAGE = -1;
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    public static final class Row {
        public final Tag tag;
        @Nullable public final String displayName;

        private Row(Tag tag, @Nullable String displayName) {
            this.tag = tag;
            this.displayName = displayName;
        }
    }

    private final LinkedHashMap<Integer, List<Row>> pages =
        new LinkedHashMap<>(CACHED_PAGES, 0.75f, true);
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long generation;
    private volatile boolean closed;
    private long displayedGeneration;
    private Cursor active;
    private int count;
    private int pending = NO_PAGE;
    private int wanted = NO_PAGE;
    private BooleanSupplier visible;
    private BiConsumer<Integer, Integer> replaced;
    private BiConsumer<Integer, Integer> changed;

    public TagRowPages() {
        this(WORKER);
    }

    public TagRowPages(Executor worker) {
        this.worker = worker;
    }

    public void load(Supplier<Cursor> query, BooleanSupplier currentVisibility,
                     BiConsumer<Integer, Integer> replaced, BiConsumer<Integer, Integer> pageChanged) {
        if (closed) return;
        long request = ++generation;
        pending = wanted = NO_PAGE;
        worker.execute(() -> {
            if (!current(request)) return;
            closeCursor();
            try {
                active = query.get();
                int total = active.getCount();
                List<Row> first = readRows(active, 0, total);
                if (!current(request)) {
                    closeCursor();
                    return;
                }
                main.post(() -> {
                    if (!current(request)) return;
                    boolean allowed = currentVisibility.getAsBoolean();
                    int previous = count;
                    pages.clear();
                    count = allowed ? total : 0;
                    if (allowed && !first.isEmpty()) pages.put(0, first);
                    displayedGeneration = request;
                    visible = currentVisibility;
                    this.replaced = replaced;
                    changed = pageChanged;
                    replaced.accept(previous, count);
                });
            } catch (RuntimeException error) {
                closeCursor();
                LogUtility.w("Could not load the tag list", error);
            }
        });
    }

    public int size() {
        return count;
    }

    @Nullable
    public Row get(int position) {
        if (position < 0 || position >= count) return null;
        int start = position - position % PAGE_SIZE;
        List<Row> cached = pages.get(start);
        if (cached != null && position - start < cached.size()) return cached.get(position - start);
        if (displayedGeneration == generation && start != pending && !closed) {
            if (pending == NO_PAGE) requestPage(start);
            else wanted = start;
        }
        return null;
    }

    private void requestPage(int start) {
        pending = start;
        long request = generation;
        int total = count;
        worker.execute(() -> {
            if (!current(request)) return;
            List<Row> read = null;
            try {
                read = readRows(active, start, total);
            } catch (RuntimeException error) {
                LogUtility.w("Could not load a page of tags", error);
            }
            List<Row> result = read;
            main.post(() -> {
                if (!current(request)) return;
                pending = NO_PAGE;
                int next = wanted;
                wanted = NO_PAGE;
                if (!visible.getAsBoolean()) {
                    int previous = count;
                    BiConsumer<Integer, Integer> notify = replaced;
                    clear();
                    notify.accept(previous, 0);
                    return;
                }
                if (result != null && !result.isEmpty()) {
                    pages.put(start, result);
                    if (pages.size() > CACHED_PAGES) pages.remove(pages.keySet().iterator().next());
                    changed.accept(start, result.size());
                }
                if (current(request) && pending == NO_PAGE && next != NO_PAGE
                    && next != start && !pages.containsKey(next)) requestPage(next);
            });
        });
    }

    private static List<Row> readRows(Cursor cursor, int start, int total) {
        int length = Math.min(PAGE_SIZE, total - start);
        ArrayList<Row> result = new ArrayList<>(length);
        int displayColumn = cursor.getColumnIndexOrThrow("displayName");
        for (int index = 0; index < length && cursor.moveToPosition(start + index); index++) {
            result.add(new Row(Queries.TagTable.cursorToTag(cursor), cursor.getString(displayColumn)));
        }
        return result;
    }

    private boolean current(long request) {
        return !closed && request == generation;
    }

    private void closeCursor() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    public void clear() {
        ++generation;
        count = 0;
        pending = wanted = NO_PAGE;
        visible = null;
        replaced = null;
        changed = null;
        pages.clear();
        worker.execute(this::closeCursor);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clear();
    }
}
