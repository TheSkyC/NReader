// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.converters;

import com.maxwai.nclientv3.api.local.LocalGallery;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExportFileTest {
    @Test
    public void sanitizesPathCharactersAndProvidesFallbackName() {
        assertEquals("title___name", ExportFile.safeBaseName(" title:/\\name ", 4));
        assertEquals("gallery-4", ExportFile.safeBaseName("...", 4));
        assertTrue(ExportFile.safeBaseName("a".repeat(200), 4).length() <= 120);
    }

    @Test
    public void reservesUniqueNameAndPublishesAtomically() throws Exception {
        File directory = Files.createTempDirectory("nreader-export-test").toFile();
        try {
            ExportFile first = ExportFile.create(directory, "same", 1, ".zip");
            Files.write(first.getTemporaryFile().toPath(), new byte[]{1, 2, 3});
            first.publish();
            first.close();
            ExportFile second = ExportFile.create(directory, "same", 1, ".zip");
            assertTrue(second.getOutputFile().getName().contains("(2)"));
            second.close();
            assertTrue(new File(directory, "same.zip").isFile());
            assertFalse(new File(directory, "same.zip.part").exists());
        } finally {
            delete(directory);
        }
    }

    @Test
    public void galleryCanBeReconstructedFromPersistedDirectoryPath() throws Exception {
        File directory = Files.createTempDirectory("nreader-gallery-restore").toFile();
        try {
            Files.write(new File(directory, ".214").toPath(), new byte[0]);
            Files.write(new File(directory, ".nomedia").toPath(), "{\"num_pages\":1}".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(directory, "001.png").toPath(), new byte[]{1, 2, 3});

            LocalGallery restored = CreatePdfOrZip.loadGallery(directory.getAbsolutePath());

            assertTrue(restored != null && restored.isValid());
            assertEquals(214, restored.getId());
            assertEquals(1, restored.getPageCount());
        } finally {
            delete(directory);
        }
    }

    @Test
    public void sparseGalleryExportsAllExistingPagesInNumericOrder() throws Exception {
        File directory = Files.createTempDirectory("nreader-sparse-export").toFile();
        try {
            Files.write(new File(directory, ".214").toPath(), new byte[0]);
            Files.write(new File(directory, ".nomedia").toPath(), "{\"num_pages\":2}".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(directory, "010.png").toPath(), new byte[]{10});
            Files.write(new File(directory, "002.jpg").toPath(), new byte[]{2});
            LocalGallery gallery = CreatePdfOrZip.loadGallery(directory.getAbsolutePath());
            List<com.maxwai.nclientv3.files.PageFile> pages = CreatePdfOrZip.exportPages(gallery);
            assertEquals(2, pages.size());
            assertEquals("002.jpg", pages.get(0).getName());
            assertEquals("010.png", pages.get(1).getName());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (File page : pages) CreatePdfOrZip.writeZipPage(zip, page);
            }
            List<String> names = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            }
            assertEquals(java.util.Arrays.asList("002.jpg", "010.png"), names);
        } finally {
            delete(directory);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
