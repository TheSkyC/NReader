// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReaderPrefetchQueue {
    public static final int MAX_CONCURRENT = 2;

    public interface Backend {
        Cancellable start(int page, @NonNull String sourceKey, @NonNull Runnable onComplete);
    }

    public interface Cancellable {
        void cancel();
    }

    public static final class Request {
        public final int page;
        @NonNull final String sourceKey;

        public Request(int page, @NonNull String sourceKey) {
            this.page = page;
            this.sourceKey = sourceKey;
        }
    }

    private final Backend backend;
    private final Map<Integer, Entry> active = new LinkedHashMap<>();
    private final Map<Integer, String> completed = new LinkedHashMap<>();
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private Map<Integer, String> desired = new LinkedHashMap<>();

    public ReaderPrefetchQueue(@NonNull Backend backend) {
        this.backend = backend;
    }

    public void update(@NonNull List<Request> requests) {
        Map<Integer, String> nextDesired = new LinkedHashMap<>();
        for (Request request : requests) {
            if (request.page >= 0 && request.sourceKey.length() > 0)
                nextDesired.putIfAbsent(request.page, request.sourceKey);
        }

        for (Entry entry : new ArrayList<>(active.values())) {
            if (!Objects.equals(nextDesired.get(entry.page), entry.sourceKey)) cancel(entry);
        }
        completed.entrySet().removeIf(entry ->
            !Objects.equals(nextDesired.get(entry.getKey()), entry.getValue()));
        desired = nextDesired;
        pending.clear();
        for (Map.Entry<Integer, String> request : desired.entrySet()) {
            if (!active.containsKey(request.getKey())
                && !Objects.equals(completed.get(request.getKey()), request.getValue()))
                pending.addLast(new Request(request.getKey(), request.getValue()));
        }
        startNext();
    }

    public void cancelAll() {
        desired.clear();
        pending.clear();
        completed.clear();
        for (Entry entry : new ArrayList<>(active.values())) cancel(entry);
    }

    public int activeCount() {
        return active.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    private void startNext() {
        while (active.size() < MAX_CONCURRENT && !pending.isEmpty()) {
            Request request = pending.removeFirst();
            if (!Objects.equals(desired.get(request.page), request.sourceKey)
                || active.containsKey(request.page)) continue;

            Entry entry = new Entry(request.page, request.sourceKey);
            active.put(request.page, entry);
            try {
                entry.cancellable = backend.start(request.page, request.sourceKey, () -> complete(entry));
            } catch (RuntimeException error) {
                complete(entry);
            }
        }
    }

    private void complete(Entry entry) {
        if (entry.cancelled || active.get(entry.page) != entry) return;
        active.remove(entry.page);
        if (Objects.equals(desired.get(entry.page), entry.sourceKey))
            completed.put(entry.page, entry.sourceKey);
        startNext();
    }

    private void cancel(Entry entry) {
        if (active.get(entry.page) != entry) return;
        active.remove(entry.page);
        entry.cancelled = true;
        if (entry.cancellable != null) {
            try {
                entry.cancellable.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class Entry {
        final int page;
        @NonNull final String sourceKey;
        Cancellable cancellable;
        boolean cancelled;

        Entry(int page, @NonNull String sourceKey) {
            this.page = page;
            this.sourceKey = sourceKey;
        }
    }
}
