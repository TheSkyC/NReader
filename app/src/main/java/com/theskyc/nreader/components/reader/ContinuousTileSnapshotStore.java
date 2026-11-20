// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Owns a locked cache directory and reserves space for live page snapshots. */
final class ContinuousTileSnapshotStore implements AutoCloseable {
    private final File directory;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final FileOutputStream lockStream;
    private final FileLock lock;
    private final Set<Lease> pendingCleanup = new HashSet<>();
    private long reservedBytes;
    private int active;
    private boolean poisoned;
    private boolean closed;

    private ContinuousTileSnapshotStore(File directory, long maxFileBytes, long maxTotalBytes,
                                        FileOutputStream lockStream, FileLock lock) {
        this.directory = directory;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.lockStream = lockStream;
        this.lock = lock;
    }

    @NonNull
    static ContinuousTileSnapshotStore open(@NonNull File directory, long maxFileBytes, long maxTotalBytes)
        throws IOException {
        if (maxFileBytes <= 0 || maxTotalBytes < maxFileBytes)
            throw new IllegalArgumentException("Invalid image snapshot budget");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Could not create image snapshot directory");
        FileOutputStream stream = new FileOutputStream(new File(directory, ".lock"), true);
        FileLock acquired = null;
        try {
            try {
                acquired = stream.getChannel().tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Image snapshot directory is already in use", exception);
            }
            if (acquired == null) throw new IOException("Image snapshot directory is already in use");
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Could not inspect image snapshot directory");
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith("reader-tile-") || !name.endsWith(".snapshot")) continue;
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || !file.delete())
                    throw new IOException("Could not remove stale image snapshot");
            }
            return new ContinuousTileSnapshotStore(directory, maxFileBytes, maxTotalBytes, stream, acquired);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                if (acquired != null) acquired.release();
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            try {
                stream.close();
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    @NonNull
    Lease create(@NonNull File source, @NonNull BooleanSupplier canceled) throws IOException {
        retryPendingCleanup();
        synchronized (this) {
            if (closed || poisoned) throw new IOException("Image snapshot store is unavailable");
            if (maxFileBytes > maxTotalBytes - reservedBytes) throw new BudgetExceededException();
            reservedBytes += maxFileBytes;
            active++;
        }
        ContinuousTileSnapshot snapshot = null;
        try {
            snapshot = ContinuousTileSnapshot.create(source, directory, maxFileBytes, canceled);
            return new Lease(this, snapshot);
        } catch (IOException | RuntimeException | Error exception) {
            boolean cleanupFailed = exception instanceof ContinuousTileSnapshot.CleanupException;
            if (snapshot != null) {
                try {
                    snapshot.close();
                } catch (IOException cleanup) {
                    cleanupFailed = true;
                    exception.addSuppressed(cleanup);
                }
            }
            synchronized (this) {
                if (cleanupFailed) poisoned = true;
                reservedBytes -= maxFileBytes;
                active--;
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        retryPendingCleanup();
        synchronized (this) {
            if (closed) return;
            if (active != 0) throw new IOException("Image snapshots are still active");
            closed = true;
            try {
                lock.release();
            } finally {
                lockStream.close();
            }
        }
    }

    private void retryPendingCleanup() {
        Lease[] pending;
        synchronized (this) {
            pending = pendingCleanup.toArray(new Lease[0]);
        }
        for (Lease lease : pending) {
            try {
                lease.close();
            } catch (IOException ignored) {
                // The reservation stays held until a later retry succeeds.
            }
        }
    }

    static final class BudgetExceededException extends IOException {
        BudgetExceededException() { super("Image snapshot budget exhausted"); }
    }

    static final class Lease implements AutoCloseable {
        private final ContinuousTileSnapshotStore store;
        private final ContinuousTileSnapshot snapshot;
        private boolean closed;
        private boolean closing;

        private Lease(ContinuousTileSnapshotStore store, ContinuousTileSnapshot snapshot) {
            this.store = store;
            this.snapshot = snapshot;
        }

        @NonNull
        synchronized File file() {
            if (closing) throw new IllegalStateException("Image snapshot lease is closed");
            return snapshot.file();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closing = true;
            try {
                snapshot.close();
            } catch (IOException failure) {
                synchronized (store) { store.pendingCleanup.add(this); }
                throw failure;
            }
            synchronized (store) {
                store.pendingCleanup.remove(this);
                store.reservedBytes -= store.maxFileBytes;
                store.active--;
            }
            closed = true;
        }
    }
}
