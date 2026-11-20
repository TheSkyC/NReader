// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns a bounded, private copy used by all image requests in one page session. */
final class ContinuousTileSnapshot implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final File file;
    private boolean closed;

    private ContinuousTileSnapshot(@NonNull File file) {
        this.file = file;
    }

    @NonNull
    static ContinuousTileSnapshot create(@NonNull File source, @NonNull File directory, long maxBytes,
                                         @NonNull BooleanSupplier canceled)
        throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Invalid image snapshot limit");
        checkCanceled(canceled);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Could not create image snapshot directory");
        BasicFileAttributes before = Files.readAttributes(source.toPath(), BasicFileAttributes.class);
        if (!before.isRegularFile()) throw new IOException("Image snapshot source is not a regular file");
        File temporary = File.createTempFile("reader-tile-", ".snapshot", directory);
        try {
            long total = 0;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while (true) {
                    checkCanceled(canceled);
                    count = input.read(buffer);
                    checkCanceled(canceled);
                    if (count == -1) break;
                    if (count > maxBytes - total) throw new IOException("Image snapshot exceeds size limit");
                    output.write(buffer, 0, count);
                    total += count;
                }
                if (total == 0) throw new IOException("Empty image snapshot");
            }
            checkCanceled(canceled);
            BasicFileAttributes after = Files.readAttributes(source.toPath(), BasicFileAttributes.class);
            if (!after.isRegularFile() || total != before.size() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("Image source changed during snapshot");
            return new ContinuousTileSnapshot(temporary);
        } catch (IOException | RuntimeException | Error failure) {
            if (temporary.exists() && !temporary.delete())
                throw new CleanupException(failure);
            throw failure;
        }
    }

    static final class CleanupException extends IOException {
        CleanupException(Throwable cause) { super("Could not remove incomplete image snapshot", cause); }
    }

    private static void checkCanceled(@NonNull BooleanSupplier canceled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || canceled.getAsBoolean())
            throw new InterruptedIOException("Image snapshot interrupted");
    }

    @NonNull
    synchronized File file() {
        if (closed) throw new IllegalStateException("Image snapshot is closed");
        return file;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (file.exists() && !file.delete()) throw new IOException("Could not remove image snapshot");
        closed = true;
    }
}
