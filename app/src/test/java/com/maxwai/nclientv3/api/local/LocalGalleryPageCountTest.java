// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api.local;

import android.app.Application;
import android.os.Parcel;

import com.maxwai.nclientv3.components.reader.ContinuousPageAdapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalGalleryPageCountTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sparseLocalGalleryKeepsNumberedGapsAndSurvivesParcel() throws Exception {
        File directory = temporary.newFolder();
        assertTrue(new File(directory, "001.png").createNewFile());
        assertTrue(new File(directory, "003.jpg").createNewFile());
        LocalGallery gallery = new LocalGallery(directory, true);
        assertTrue(gallery.isValid());
        assertEquals(3, gallery.getPageCount());
        assertNotNull(gallery.getPage(1));
        assertNull(gallery.getPage(2));
        assertNotNull(ContinuousPageAdapter.pageSource(gallery, gallery.getGalleryFolder(), 2));

        Parcel parcel = Parcel.obtain();
        try {
            gallery.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            LocalGallery restored = LocalGallery.CREATOR.createFromParcel(parcel);
            assertEquals(3, restored.getPageCount());
            assertNotNull(restored.getPage(3));
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void metadataKeepsItsDeclaredPageCount() throws Exception {
        File directory = temporary.newFolder();
        assertTrue(new File(directory, "001.png").createNewFile());
        assertTrue(new File(directory, "003.jpg").createNewFile());
        Files.write(new File(directory, ".nomedia").toPath(), "{\"num_pages\":5}".getBytes(StandardCharsets.UTF_8));
        assertEquals(5, new LocalGallery(directory).getPageCount());
        Files.write(new File(directory, ".nomedia").toPath(), "{\"num_pages\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, new LocalGallery(directory).getPageCount());
    }

    @Test
    public void extremeAndZeroPageNumbersRetainBoundedLegacyFallback() throws Exception {
        File directory = temporary.newFolder();
        assertTrue(new File(directory, "1.png").createNewFile());
        assertTrue(new File(directory, "3.png").createNewFile());
        assertTrue(new File(directory, "999999999.png").createNewFile());
        assertEquals(3, new LocalGallery(directory, true).getPageCount());
        File onlyZero = temporary.newFolder();
        assertTrue(new File(onlyZero, "0.png").createNewFile());
        assertEquals(1, new LocalGallery(onlyZero, true).getPageCount());
        File invalidOnly = temporary.newFolder();
        assertTrue(new File(invalidOnly, "999999999.png").createNewFile());
        assertEquals(1, new LocalGallery(invalidOnly, true).getPageCount());
        File empty = temporary.newFolder();
        assertEquals(0, new LocalGallery(empty, true).getPageCount());
        File atLimit = temporary.newFolder();
        assertTrue(new File(atLimit, "10000.png").createNewFile());
        assertEquals(10000, new LocalGallery(atLimit, true).getPageCount());
    }
}
