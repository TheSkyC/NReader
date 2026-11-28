package com.theskyc.nreader.components.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class ManagedCacheStorageTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void measuresRegisteredCacheDirectoriesAndExcludesRestoreAndStagingFiles() throws IOException {
        File cacheRoot = temporaryFolder.newFolder("cache");
        write(new File(CacheDirectories.imageDiskCache(cacheRoot), "image.bin"), 3);
        write(new File(CacheDirectories.favoritePageCache(cacheRoot), "pages.db"), 5);
        write(new File(CacheDirectories.favoritePageCache(cacheRoot), "pages.db-wal"), 2);
        write(new File(CacheDirectories.searchPageCache(cacheRoot), "pages.db"), 4);
        write(new File(CacheDirectories.readerTileSnapshots(cacheRoot), "reader-tile-1.snapshot"), 11);
        write(new File(cacheRoot, "last-restore-snapshot.zip"), 13);
        write(new File(new File(cacheRoot, "tag-catalog"), "catalog.part"), 17);

        ManagedCacheStorage.Usage usage = ManagedCacheStorage.measure(cacheRoot);

        assertEquals(3, usage.imageBytes);
        assertEquals(7, usage.favoritePageBytes);
        assertEquals(4, usage.searchPageBytes);
        assertEquals(11, usage.readerSnapshotBytes);
        assertEquals(25, usage.totalBytes());
    }

    private static void write(File file, int size) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create test directory");
        Files.write(file.toPath(), new byte[size]);
    }
}
