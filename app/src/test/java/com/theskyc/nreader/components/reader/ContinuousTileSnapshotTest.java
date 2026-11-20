// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContinuousTileSnapshotTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void sourceReplacementDoesNotChangeBothReadersSnapshot() throws Exception {
        File source = folder.newFile("page.png");
        byte[] oldBytes = {1, 2, 3, 4};
        byte[] newBytes = {5, 6, 7, 8};
        Files.write(source.toPath(), oldBytes);
        File cache = folder.newFolder("cache");
        ContinuousTileSnapshot snapshot = ContinuousTileSnapshot.create(source, cache, 4, () -> false);
        File replacement = folder.newFile("replacement.png");
        Files.write(replacement.toPath(), newBytes);
        Files.move(replacement.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File retained = snapshot.file();
        assertArrayEquals(oldBytes, Files.readAllBytes(retained.toPath()));
        assertArrayEquals(oldBytes, Files.readAllBytes(snapshot.file().toPath()));
        assertArrayEquals(newBytes, Files.readAllBytes(source.toPath()));
        snapshot.close();
        snapshot.close();
        assertFalse(retained.exists());
        assertThrows(IllegalStateException.class, snapshot::file);
    }

    @Test
    public void inPlaceModificationDuringCopyRejectsAndRemovesSnapshot() throws Exception {
        File source = folder.newFile("changing.png");
        Files.write(source.toPath(), new byte[256 * 1024]);
        FileTime before = Files.getLastModifiedTime(source.toPath());
        File cache = folder.newFolder("changing-cache");
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> ContinuousTileSnapshot.create(source, cache, 256 * 1024, () -> {
            if (checks.incrementAndGet() == 3) {
                try (RandomAccessFile writer = new RandomAccessFile(source, "rw")) {
                    writer.seek(128 * 1024);
                    writer.writeByte(42);
                    Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(before.toMillis() + 10000));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
            return false;
        }));
        assertEquals(0, cache.listFiles().length);
    }

    @Test
    public void limitAndInterruptedCopyRemovePartialFiles() throws Exception {
        File source = folder.newFile("page.png");
        Files.write(source.toPath(), new byte[128]);
        File cache = folder.newFolder("cache");
        assertThrows(IOException.class, () -> ContinuousTileSnapshot.create(source, cache, 127, () -> false));
        assertEquals(0, cache.listFiles().length);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ContinuousTileSnapshot.create(source, cache, 128, () -> false));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, cache.listFiles().length);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedIOException.class,
            () -> ContinuousTileSnapshot.create(source, cache, 128, () -> checks.incrementAndGet() == 3));
        assertEquals(0, cache.listFiles().length);
        try (ContinuousTileSnapshot snapshot = ContinuousTileSnapshot.create(source, cache, 128, () -> false)) {
            assertTrue(snapshot.file().isFile());
        }
        assertEquals(0, cache.listFiles().length);
    }
}
