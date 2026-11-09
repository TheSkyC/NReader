// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.files;

import android.app.Application;
import android.util.JsonReader;

import com.maxwai.nclientv3.api.components.GalleryData;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalDownloadStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9ZkAAAAASUVORK5CYII=");

    @Test
    public void directoryAndFileCountNeverProveCompletion() throws Exception {
        File folder = temporary.newFolder();
        assertEquals(LocalDownloadState.UNVERIFIED, LocalDownloadState.inspect(folder));
        metadata(folder, 3);
        Files.write(new File(folder, "001.png").toPath(), PNG);
        Files.write(new File(folder, "003.png").toPath(), PNG);
        assertEquals(LocalDownloadState.PARTIAL, LocalDownloadState.inspect(folder));
        Files.write(new File(folder, "002.png").toPath(), PNG);
        assertEquals(LocalDownloadState.COMPLETE, LocalDownloadState.inspect(folder));
        Files.write(new File(folder, "002.png").toPath(), new byte[]{1, 2, 3});
        assertEquals(LocalDownloadState.DAMAGED, LocalDownloadState.inspect(folder));
        Files.write(new File(folder, "002.png").toPath(), new byte[0]);
        assertEquals(LocalDownloadState.DAMAGED, LocalDownloadState.inspect(folder));
    }

    @Test
    public void invalidMetadataIsUnverifiedAndVerificationIsBounded() throws Exception {
        File folder = temporary.newFolder();
        metadata(folder, 10001);
        assertEquals(LocalDownloadState.UNVERIFIED, LocalDownloadState.inspect(folder));
        Files.write(new File(folder, ".nomedia").toPath(), "{".getBytes(StandardCharsets.UTF_8));
        assertEquals(LocalDownloadState.UNVERIFIED, LocalDownloadState.inspect(folder));
        assertEquals(LocalDownloadState.ABSENT, LocalDownloadState.inspect(null));
    }

    @Test
    public void localPageInfoRetainsExpectedTotalFromMetadata() throws Exception {
        File folder = temporary.newFolder();
        Files.write(new File(folder, "003.png").toPath(), PNG);
        GalleryData data = new GalleryData(new JsonReader(new StringReader("{\"num_pages\":5}")));
        data.setPageInfo(new GalleryFolder(folder));
        assertEquals(5, data.getPageCount());
        assertNotNull(data.getCover().getImagePath());
    }

    private static void metadata(File folder, int count) throws Exception {
        Files.write(new File(folder, ".nomedia").toPath(), ("{\"num_pages\":" + count + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void folderRecognizesLongSiteIdsWithoutChangingLegacyMarkers() throws Exception {
        for (int id : new int[]{123456, 1234567, 999000001}) {
            File folder = temporary.newFolder();
            assertTrue(new File(folder, "." + id).createNewFile());
            assertEquals(id, new GalleryFolder(folder).getId());
        }
    }
}
