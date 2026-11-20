// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Byte- and entry-bounded cache shared by image sessions in one reader. */
final class ContinuousTileCache {
    enum StoreResult { STORED, NO_CAPACITY, INVALID, TOO_LARGE }

    private final int maxBytes;
    private final int maxEntries;
    private final int maxTileBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final List<Session> sessions = new ArrayList<>();
    private long bytes;

    ContinuousTileCache(int maxBytes, int maxEntries, int maxTileBytes) {
        if (maxBytes <= 0 || maxEntries <= 0 || maxTileBytes <= 0)
            throw new IllegalArgumentException("Invalid tile cache limits");
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.maxTileBytes = maxTileBytes;
    }

    @NonNull
    Session newSession() {
        return newSession(null);
    }

    @NonNull
    Session newSession(@Nullable Runnable onBudgetChanged) {
        Session session = new Session(this, onBudgetChanged);
        List<Runnable> listeners;
        synchronized (this) {
            sessions.add(session);
            rebalance();
            listeners = budgetListeners(session);
        }
        for (Runnable listener : listeners) listener.run();
        return session;
    }

    synchronized int capacity(@NonNull Session session) {
        if (session.owner != this || !session.active || sessions.isEmpty()) return 0;
        int index = sessions.indexOf(session);
        if (index < 0) return 0;
        int slots = Math.min(maxEntries, maxBytes / maxTileBytes);
        return slots / sessions.size() + (index < slots % sessions.size() ? 1 : 0);
    }

    boolean supportsDecoderTiles() {
        return maxTileBytes >= ContinuousTileDecoder.MAX_TILE_BYTES;
    }

    @Nullable
    synchronized Bitmap get(@NonNull Session session, @NonNull Rect region, int sample) {
        if (session.owner != this || !session.active) return null;
        Key key = new Key(session, region, sample);
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (!entry.bitmap.isRecycled()) return entry.bitmap;
        entries.remove(key);
        bytes -= entry.bytes;
        return null;
    }

    @NonNull
    synchronized StoreResult put(@NonNull Session session, @NonNull Rect region, int sample, @NonNull Bitmap bitmap) {
        if (session.owner != this || !session.active || bitmap.isRecycled()) return StoreResult.INVALID;
        int size = bitmap.getAllocationByteCount();
        int capacity = capacity(session);
        if (size <= 0 || size > maxTileBytes) return StoreResult.TOO_LARGE;
        if (capacity == 0) return StoreResult.NO_CAPACITY;
        Key key = new Key(session, region, sample);
        Entry previous = entries.put(key, new Entry(bitmap, size));
        if (previous != null) bytes -= previous.bytes;
        bytes += size;
        trimSession(session, capacity);
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while ((bytes > maxBytes || entries.size() > maxEntries) && iterator.hasNext()) {
            bytes -= iterator.next().getValue().bytes;
            iterator.remove();
        }
        return StoreResult.STORED;
    }

    void removeSession(@NonNull Session session) {
        List<Runnable> listeners;
        synchronized (this) {
            if (session.owner != this || !session.active) return;
            session.active = false;
            sessions.remove(session);
            Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Entry> item = iterator.next();
                if (item.getKey().session != session) continue;
                bytes -= item.getValue().bytes;
                iterator.remove();
            }
            rebalance();
            listeners = budgetListeners(null);
        }
        for (Runnable listener : listeners) listener.run();
    }

    @NonNull
    private List<Runnable> budgetListeners(@Nullable Session exclude) {
        List<Runnable> listeners = new ArrayList<>();
        for (Session session : sessions) {
            if (session == exclude || session.onBudgetChanged == null) continue;
            Runnable listener = session.onBudgetChanged.get();
            if (listener != null) listeners.add(listener);
        }
        return listeners;
    }

    private void rebalance() {
        for (Session session : sessions) trimSession(session, capacity(session));
    }

    private void trimSession(@NonNull Session session, int capacity) {
        int count = 0;
        for (Key key : entries.keySet()) if (key.session == session) count++;
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (count > capacity && iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next();
            if (item.getKey().session != session) continue;
            bytes -= item.getValue().bytes;
            iterator.remove();
            count--;
        }
    }

    void clear() {
        List<Runnable> listeners;
        synchronized (this) {
            entries.clear();
            bytes = 0;
            listeners = budgetListeners(null);
        }
        for (Runnable listener : listeners) listener.run();
    }

    static final class Session {
        private final ContinuousTileCache owner;
        @Nullable private final WeakReference<Runnable> onBudgetChanged;
        private boolean active = true;

        private Session(ContinuousTileCache owner, @Nullable Runnable onBudgetChanged) {
            this.owner = owner;
            this.onBudgetChanged = onBudgetChanged == null ? null : new WeakReference<>(onBudgetChanged);
        }
    }

    private static final class Entry {
        final Bitmap bitmap;
        final int bytes;

        Entry(Bitmap bitmap, int bytes) {
            this.bitmap = bitmap;
            this.bytes = bytes;
        }
    }

    private static final class Key {
        final Session session;
        final int left, top, right, bottom, sample;

        Key(Session session, Rect region, int sample) {
            this.session = session;
            left = region.left;
            top = region.top;
            right = region.right;
            bottom = region.bottom;
            this.sample = sample;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return session == key.session && left == key.left && top == key.top
                && right == key.right && bottom == key.bottom && sample == key.sample;
        }

        @Override public int hashCode() {
            int hash = System.identityHashCode(session);
            hash = 31 * hash + left;
            hash = 31 * hash + top;
            hash = 31 * hash + right;
            hash = 31 * hash + bottom;
            return 31 * hash + sample;
        }
    }
}
