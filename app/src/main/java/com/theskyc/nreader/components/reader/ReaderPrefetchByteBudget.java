// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ReaderPrefetchByteBudget implements AutoCloseable {
    public static final String TOKEN_HEADER = "X-NReader-Prefetch-Token";
    private static final ConcurrentMap<String, ReaderPrefetchByteBudget> ACTIVE = new ConcurrentHashMap<>();

    private final String token = UUID.randomUUID().toString();
    private long limitBytes;
    private long consumedBytes;
    private boolean closed;

    public ReaderPrefetchByteBudget(long limitBytes) {
        if (limitBytes <= 0) throw new IllegalArgumentException("Prefetch byte budget must be positive");
        this.limitBytes = limitBytes;
        ACTIVE.put(token, this);
    }

    public String token() {
        return token;
    }

    @Nullable
    public static ReaderPrefetchByteBudget find(String token) {
        return ACTIVE.get(token);
    }

    public synchronized void setLimit(long limitBytes) {
        if (limitBytes < 0) throw new IllegalArgumentException("Prefetch byte budget cannot be negative");
        this.limitBytes = limitBytes;
    }

    public synchronized long remainingBytes() {
        return closed || consumedBytes >= limitBytes ? 0 : limitBytes - consumedBytes;
    }

    public synchronized long consumedBytes() {
        return consumedBytes;
    }

    public synchronized void recordConsumed(long bytes) {
        if (bytes <= 0) return;
        consumedBytes = consumedBytes > Long.MAX_VALUE - bytes
            ? Long.MAX_VALUE : consumedBytes + bytes;
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        ACTIVE.remove(token, this);
    }
}
