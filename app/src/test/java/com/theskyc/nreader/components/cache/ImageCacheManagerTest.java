package com.theskyc.nreader.components.cache;

import android.app.Application;

import com.theskyc.nreader.async.database.export.Importer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ImageCacheManagerTest {
    @Test
    public void imageCacheMeasurementDoesNotCountRestoreSnapshots() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File imageDirectory = ImageCacheManager.imageCacheDirectory(application);
        assertTrue(imageDirectory.mkdirs() || imageDirectory.isDirectory());
        try (FileOutputStream output = new FileOutputStream(new File(imageDirectory, "image.bin"))) {
            output.write(new byte[]{1, 2, 3});
        }
        try (FileOutputStream output = new FileOutputStream(Importer.snapshotFile(application))) {
            output.write(new byte[32]);
        }

        assertEquals(3, ImageCacheManager.directorySize(imageDirectory));
    }

    @Test
    public void clearingGlideImageCacheKeepsRestoreSnapshot() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File imageDirectory = ImageCacheManager.imageCacheDirectory(application);
        assertTrue(imageDirectory.mkdirs() || imageDirectory.isDirectory());
        File staleImage = new File(imageDirectory, "stale-image-entry");
        Files.write(staleImage.toPath(), new byte[]{1, 2, 3});
        File restoreSnapshot = Importer.snapshotFile(application);
        Files.write(restoreSnapshot.toPath(), new byte[]{4, 5, 6});

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> ImageCacheManager.clearDiskCache(application)).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(staleImage.exists());
        assertTrue(restoreSnapshot.isFile());
    }
}
