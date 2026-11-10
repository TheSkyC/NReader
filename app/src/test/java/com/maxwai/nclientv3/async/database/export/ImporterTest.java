// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.components.status.StatusManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ImporterTest {
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("Entries.db");
        helper = new DatabaseHelper(RuntimeEnvironment.getApplication());
        database = helper.getWritableDatabase();
        Queries.ResumeTable.insert(123, 7);
        Queries.ReadingProgressTable.insert(123, new ReadingPosition(9, 0.5f));
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void legacyBackupPreservesManualMeaningAndClearsAbsentAutoPositions() throws IOException {
        restore("{\"Resume\":[{\"id_gallery\":123,\"page\":4}],\"FutureTable\":[]}");
        assertEquals(4, Queries.ResumeTable.pageFromId(123));
        assertNull(Queries.ReadingProgressTable.fromId(123));
        assertFalse(database.inTransaction());
    }

    @Test
    public void automaticOffsetRoundTripsWithoutChangingManualBookmark() throws IOException {
        restore("{\"ReadingProgress\":[{\"id_gallery\":123,\"page\":11,\"offset\":0.625,\"updated_at\":99}]}");
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
        ReadingPosition position = Queries.ReadingProgressTable.fromId(123);
        assertNotNull(position);
        assertEquals(11, position.page);
        assertEquals(0.625f, position.offset, 0f);
    }

    @Test
    public void malformedOrInvalidRowsRollBackAllDatabaseChanges() throws IOException {
        String[] invalidBackups = {
            "{\"Resume\":[{\"id_gallery\":123,\"page\":4}],\"ReadingProgress\":[{\"id_gallery\":123,\"page\":0,\"updated_at\":99}]}",
            "{\"Resume\":[{\"id_gallery\":123,\"page\":4}],\"ReadingProgress\":[",
            "{\"Resume\":[],\"Resume\":[]}",
            "{\"Resume\":[],\"ReadingProgress\":[{\"id_gallery\":123,\"page\":\"invalid\",\"offset\":0,\"updated_at\":99}]}",
            "{\"Resume\":[],\"ReadingProgress\":[{\"id_gallery\":123,\"page\":2147483648,\"updated_at\":99}]}",
            "{\"Resume\":[]}{}"
        };
        for (String backup : invalidBackups) {
            try {
                restore(backup);
                fail("Expected backup failure");
            } catch (IOException expected) {
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
                assertFalse(database.inTransaction());
            }
        }
    }

    private void restore(String json) throws IOException {
        Importer.importDB(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void successfulRestoreReplacesStatusCatalogAndFailureLeavesItIntact() throws IOException {
        StatusManager.add("Removed status", 123);
        restore("{\"Status\":[{\"name\":\"Restored status\",\"color\":456}]}");
        assertFalse(StatusManager.getNames().contains("Removed status"));
        assertTrue(StatusManager.getNames().contains("Restored status"));
        assertEquals(0x7f000000 | 456, StatusManager.getByName("Restored status").color);
        assertNull(StatusManager.getByName("New status name"));
        database.execSQL("INSERT INTO StatusManga VALUES (123, 'Orphan status', 1)");
        assertEquals(StatusManager.DEFAULT_STATUS, Queries.StatusMangaTable.getStatus(123).name);
        try {
            restore("{\"Status\":[],\"ReadingProgress\":[");
            fail("Expected backup failure");
        } catch (IOException expected) {
            assertTrue(StatusManager.getNames().contains("Restored status"));
        }
    }

    @Test
    public void savesDuringRestoreDoNotBlockCallerOrOverwriteRestoredPosition() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> restoring = callers.submit(() -> {
                try {
                    ReadingProgress.restore(() -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Restore timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                        Queries.ReadingProgressTable.insert(123, new ReadingPosition(6, 0));
                    });
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                callers.submit(() -> ReadingProgress.save(123, new ReadingPosition(20, 0))).get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            restoring.get(5, TimeUnit.SECONDS);
            ReadingProgress.load(123, position -> drained.countDown());
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertEquals(6, Queries.ReadingProgressTable.fromId(123).page);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    public void malformedLaterArchiveEntryRollsBackDatabaseAndStagedPreferences() throws IOException {
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "broken-backup.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[{\"id_gallery\":123,\"page\":4}]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Settings.json"));
            zip.write("{\"testPreference\":{\"INT\":42}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ScrapedTags.json"));
            zip.write("{".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
            fail("Expected archive failure");
        } catch (IOException expected) {
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
            assertFalse(database.inTransaction());
            assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).contains("testPreference"));
        }
    }

    @Test
    public void backupManifestRejectsTamperedDatabaseBytes() throws Exception {
        byte[] database = "{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8);
        JSONObject manifest = new JSONObject();
        manifest.put("format_version", Exporter.BACKUP_FORMAT_VERSION);
        manifest.put("database_length", database.length);
        manifest.put("database_sha256", Exporter.sha256(database));
        Importer.validateManifest(manifest.toString().getBytes(StandardCharsets.UTF_8), database);
        database[0] = '[';
        try {
            Importer.validateManifest(manifest.toString().getBytes(StandardCharsets.UTF_8), database);
            fail("Expected integrity failure");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void oversizedManifestDoesNotChangeExistingData() throws Exception {
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "oversized-manifest.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Manifest.json"));
            zip.write(new byte[65537]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
            fail("Expected oversized manifest rejection");
        } catch (IOException expected) {
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
            assertFalse(database.inTransaction());
        }
    }

    @Test
    public void invalidDownloadTaskRowsRollBackTheRestore() throws IOException {
        try {
            restore("{\"DownloadTasks\":[{\"id_gallery\":123,\"range_start\":3,\"range_end\":1,"
                + "\"title\":\"bad\",\"state\":\"PAUSED\",\"failure\":\"NONE\","
                + "\"queue_order\":1,\"metadata_attempts\":0,\"page_number\":-1,\"page_attempts\":0,\"retry_at\":0}]}");
            fail("Expected invalid task failure");
        } catch (IOException expected) {
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
        }
        try {
            restore("{\"DownloadTasks\":[{\"id_gallery\":123,\"range_start\":0,\"range_end\":0,"
                + "\"title\":\"bad metadata\",\"metadata\":\"{}\",\"state\":\"PAUSED\",\"failure\":\"NONE\","
                + "\"queue_order\":1,\"metadata_attempts\":0,\"page_number\":-1,\"page_attempts\":0,\"retry_at\":0}]}");
            fail("Expected invalid metadata failure");
        } catch (IOException expected) {
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
        }
    }
}
