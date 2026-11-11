// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.async.database.SearchPlanStore;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
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
    public void deeplyNestedUnknownDatabaseValuesFailSafely() throws Exception {
        String accepted = nestedUnknownTable(63);
        BackupPreview.validateDatabaseBytes(accepted.getBytes(StandardCharsets.UTF_8));
        restore(accepted);
        Queries.ResumeTable.insert(123, 7);
        Queries.ReadingProgressTable.insert(123, new ReadingPosition(9, 0.5f));
        String rejected = nestedUnknownTable(64);
        assertThrows(IOException.class, () -> BackupPreview.validateDatabaseBytes(rejected.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> restore(rejected));
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
        assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
        assertFalse(database.inTransaction());
    }

    private static String nestedUnknownTable(int depth) {
        StringBuilder json = new StringBuilder("{\"Resume\":[],\"Future\":[");
        for (int index = 0; index < depth; index++) json.append('[');
        json.append("null");
        for (int index = 0; index < depth; index++) json.append(']');
        return json.append("]}").toString();
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
    public void plansRoundTripAndLegacyRestoreClearsThem() throws IOException {
        ContentValues plan = new ContentValues();
        plan.put("name", "Artists");
        plan.put("query", "artist:example -tag:excluded");
        plan.put("sort", "popular-week");
        plan.put("page", 3);
        plan.put("pinned", 1);
        long first = SearchPlanStore.insert(plan);
        long second = SearchPlanStore.insert(plan);
        assertNotEquals(first, second);
        plan.put("pinned", 0);
        assertTrue(SearchPlanStore.update(first, plan));
        assertEquals(second, SearchPlanStore.list().get(0).getAsLong("id").longValue());
        byte[] backup = Exporter.currentDatabaseBytes();
        assertTrue(SearchPlanStore.delete(first));
        Importer.importDB(new ByteArrayInputStream(backup));
        assertEquals(2, SearchPlanStore.list().size());
        assertEquals("artist:example -tag:excluded", SearchPlanStore.list().get(0).getAsString("query"));
        restore("{\"Bookmark\":[]}");
        assertTrue(SearchPlanStore.list().isEmpty());
    }

    @Test
    public void invalidPlanRestoreRollsBackPreviousData() throws IOException {
        ContentValues plan = new ContentValues();
        plan.put("name", "Keep");
        plan.put("query", "tag:example");
        plan.put("sort", "");
        plan.put("page", 1);
        plan.put("pinned", 0);
        SearchPlanStore.insert(plan);
        try {
            restore("{\"SearchPlans\":[{\"id\":1,\"name\":\"bad\",\"query\":\"x\",\"sort\":\"other\",\"page\":1,\"pinned\":0}]}");
            fail("Expected invalid plan to fail");
        } catch (IOException expected) {
            assertEquals("Keep", SearchPlanStore.list().get(0).getAsString("name"));
        }
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
    public void manifestRejectsDeepUnknownFieldsAndTrailingData() throws Exception {
        byte[] databaseBytes = "{}".getBytes(StandardCharsets.UTF_8);
        String prefix = "{\"format_version\":" + Exporter.BACKUP_FORMAT_VERSION
            + ",\"database_length\":" + databaseBytes.length
            + ",\"database_sha256\":\"" + Exporter.sha256(databaseBytes) + "\",\"future\":";
        for (int depth : new int[]{63, 64}) {
            StringBuilder manifest = new StringBuilder(prefix);
            for (int index = 0; index < depth; index++) manifest.append('[');
            manifest.append("{\"nested\":true}");
            for (int index = 0; index < depth; index++) manifest.append(']');
            manifest.append('}');
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            if (depth == 63) {
                Importer.validateManifest(bytes, databaseBytes);
            } else {
                assertThrows(IOException.class, () -> Importer.validateManifest(bytes, databaseBytes));
            }
        }
        String valid = prefix + "[null,{\"nested\":true}]}";
        Importer.validateManifest(valid.getBytes(StandardCharsets.UTF_8), databaseBytes);
        assertThrows(IOException.class, () -> Importer.validateManifest((valid + "{}").getBytes(StandardCharsets.UTF_8), databaseBytes));
    }

    @Test
    public void previewAndImportAgreeOnUnknownTableDepth() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        for (int depth : new int[]{63, 64}) {
            File backup = new File(application.getCacheDir(), "depth-" + depth + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
                zip.putNextEntry(new ZipEntry("Database.json"));
                zip.write(nestedUnknownTable(depth).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Uri uri = Uri.fromFile(backup);
            if (depth == 63) {
                BackupPreview.inspect(application, uri);
                Importer.importData(application, uri);
                Queries.ResumeTable.insert(123, 7);
                Queries.ReadingProgressTable.insert(123, new ReadingPosition(9, 0.5f));
            } else {
                assertThrows(IOException.class, () -> BackupPreview.inspect(application, uri));
                assertThrows(IOException.class, () -> Importer.importData(application, uri));
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
                assertFalse(database.inTransaction());
            }
        }
    }

    @Test
    public void backupPreviewCountsRowsWithoutChangingDatabase() throws Exception {
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "preview.zip");
        byte[] databaseBytes = "{\"Resume\":[{\"id_gallery\":123,\"page\":4}],\"Favorite\":[]}".getBytes(StandardCharsets.UTF_8);
        JSONObject manifest = new JSONObject();
        manifest.put("format_version", Exporter.BACKUP_FORMAT_VERSION);
        manifest.put("database_length", databaseBytes.length);
        manifest.put("database_sha256", Exporter.sha256(databaseBytes));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write(databaseBytes);
            zip.closeEntry();
        }
        BackupPreview.Summary summary = BackupPreview.inspect(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
        assertTrue(summary.integrityChecked);
        assertEquals(1, summary.totalRows());
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
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
    public void archiveLimitsApplyToPreviewAndImportWithoutChangingData() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        for (String name : new String[]{"Settings.json", "ScrapedTags.json", "future-data"}) {
            File backup = new File(application.getCacheDir(), "large-" + name + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
                zip.putNextEntry(new ZipEntry("Database.json"));
                zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(name));
                byte[] block = new byte[8192];
                if (!"future-data".equals(name)) {
                    java.util.Arrays.fill(block, (byte) ' ');
                    zip.write("{}".getBytes(StandardCharsets.UTF_8));
                    zip.write(block, 0, block.length - 2);
                    for (int index = 0; index < 2047; index++) zip.write(block);
                } else {
                    for (int index = 0; index < 2048; index++) zip.write(block);
                }
                zip.write(0);
                zip.closeEntry();
            }
            Uri uri = Uri.fromFile(backup);
            assertThrows(IOException.class, () -> BackupPreview.inspect(application, uri));
            assertThrows(IOException.class, () -> Importer.importData(application, uri));
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
            assertFalse(database.inTransaction());
        }
    }

    @Test
    public void rejectsTooManyAndDuplicateArchiveEntriesInPreviewAndImport() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File tooMany = new File(application.getCacheDir(), "many-entries.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tooMany))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int index = 0; index < 128; index++) {
                zip.putNextEntry(new ZipEntry("future-" + index));
                zip.closeEntry();
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"Database.json", "Databasf.json"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("{}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] duplicateBytes = bytes.toByteArray();
        byte[] oldName = "Databasf.json".getBytes(StandardCharsets.UTF_8);
        byte[] newName = "Database.json".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index <= duplicateBytes.length - oldName.length; index++) {
            boolean matches = true;
            for (int position = 0; position < oldName.length; position++)
                if (duplicateBytes[index + position] != oldName[position]) matches = false;
            if (matches) System.arraycopy(newName, 0, duplicateBytes, index, newName.length);
        }
        File duplicate = new File(application.getCacheDir(), "duplicate-entries.zip");
        Files.write(duplicate.toPath(), duplicateBytes);
        for (File file : new File[]{tooMany, duplicate}) {
            Uri uri = Uri.fromFile(file);
            assertThrows(IOException.class, () -> BackupPreview.inspect(application, uri));
            assertThrows(IOException.class, () -> Importer.importData(application, uri));
            assertEquals(7, Queries.ResumeTable.pageFromId(123));
            assertFalse(database.inTransaction());
        }
    }

    @Test
    public void boundedUnknownEntriesRemainCompatible() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File backup = new File(application.getCacheDir(), "future-entry.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Future.json"));
            zip.write("future data".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Uri uri = Uri.fromFile(backup);
        assertEquals(0, BackupPreview.inspect(application, uri).totalRows());
        Importer.importData(application, uri);
        assertEquals(-1, Queries.ResumeTable.pageFromId(123));
    }

    @Test
    public void unknownEntriesUseAggregateLimitAndPreserveStagedPreferencesOnFailure() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("Settings", 0).edit().putInt("archiveBoundary", 11).commit();
        application.getSharedPreferences("ScrapedTags", 0).edit().putString("archiveBoundary", "before").commit();
        for (int extra : new int[]{0, 1}) {
            File backup = new File(application.getCacheDir(), "aggregate-" + extra + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
                zip.putNextEntry(new ZipEntry("Database.json"));
                zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("Settings.json"));
                zip.write("{\"archiveBoundary\":{\"INT\":42}}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("ScrapedTags.json"));
                zip.write("{\"archiveBoundary\":{\"STRING\":\"after\"}}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                byte[] block = new byte[8192];
                for (int part = 0; part < 2; part++) {
                    zip.putNextEntry(new ZipEntry("future-" + part));
                    for (int index = 0; index < 1024; index++) zip.write(block);
                    if (part == 1 && extra != 0) zip.write(0);
                    zip.closeEntry();
                }
            }
            Uri uri = Uri.fromFile(backup);
            if (extra == 0) {
                assertEquals(0, BackupPreview.inspect(application, uri).totalRows());
            } else {
                assertThrows(IOException.class, () -> BackupPreview.inspect(application, uri));
                assertThrows(IOException.class, () -> Importer.importData(application, uri));
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(11, application.getSharedPreferences("Settings", 0).getInt("archiveBoundary", -1));
                assertEquals("before", application.getSharedPreferences("ScrapedTags", 0).getString("archiveBoundary", null));
            }
        }
    }

    @Test
    public void restoreSnapshotReturnsDatabaseToPreImportState() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit().putInt("snapshotSetting", 11).commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("ScrapedTags", 0).edit().putString("snapshotTag", "before").commit();
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "snapshot-source.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
        assertEquals(-1, Queries.ResumeTable.pageFromId(123));
        assertTrue(Importer.snapshotFile(RuntimeEnvironment.getApplication()).isFile());
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit().putInt("snapshotSetting", 22).putBoolean("addedAfter", true).commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("ScrapedTags", 0).edit().putString("snapshotTag", "after").putBoolean("addedAfter", true).commit();
        Importer.restoreLastSnapshot(RuntimeEnvironment.getApplication());
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
        assertEquals(11, RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).getInt("snapshotSetting", -1));
        assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).contains("addedAfter"));
        assertEquals("before", RuntimeEnvironment.getApplication().getSharedPreferences("ScrapedTags", 0).getString("snapshotTag", null));
        assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("ScrapedTags", 0).contains("addedAfter"));
    }

    @Test
    public void invalidArchiveDoesNotReplaceExistingSnapshot() throws Exception {
        File valid = new File(RuntimeEnvironment.getApplication().getCacheDir(), "valid-snapshot.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(valid))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(valid));
        byte[] snapshot = Files.readAllBytes(Importer.snapshotFile(RuntimeEnvironment.getApplication()).toPath());
        File invalid = new File(RuntimeEnvironment.getApplication().getCacheDir(), "invalid-snapshot.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(invalid));
            fail("Expected invalid archive failure");
        } catch (IOException expected) {
            assertArrayEquals(snapshot, Files.readAllBytes(Importer.snapshotFile(RuntimeEnvironment.getApplication()).toPath()));
        }
    }

    @Test
    public void snapshotWriteFailurePreservesPreviousSnapshotAndData() throws Exception {
        createRestoreSnapshot();
        byte[] priorSnapshot = Files.readAllBytes(Importer.snapshotFile(RuntimeEnvironment.getApplication()).toPath());
        Queries.ResumeTable.insert(123, 7);
        Queries.ReadingProgressTable.insert(123, new ReadingPosition(9, 0.5f));
        File temporary = new File(RuntimeEnvironment.getApplication().getCacheDir(), "last-restore-snapshot.zip.part");
        Files.deleteIfExists(temporary.toPath());
        assertTrue(temporary.mkdir());
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "promotion-failure.zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
                zip.putNextEntry(new ZipEntry("Database.json"));
                zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("Settings.json"));
                zip.write("{\"promotionTest\":{\"INT\":42}}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            try {
                Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
                fail("Expected snapshot write failure");
            } catch (IOException expected) {
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
                assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).contains("promotionTest"));
                assertFalse(database.inTransaction());
                assertArrayEquals(priorSnapshot, Files.readAllBytes(Importer.snapshotFile(RuntimeEnvironment.getApplication()).toPath()));
                assertTrue(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
            }
        } finally {
            assertTrue(temporary.delete());
        }
    }

    @Test
    public void snapshotPromotionFailureRollsBackDatabase() throws Exception {
        File target = Importer.snapshotFile(RuntimeEnvironment.getApplication());
        Files.deleteIfExists(target.toPath());
        assertTrue(target.mkdir());
        File marker = new File(target, "block-replacement");
        assertTrue(marker.createNewFile());
        try {
            try {
                createRestoreSnapshot();
                fail("Expected snapshot promotion failure");
            } catch (IOException expected) {
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
                assertFalse(database.inTransaction());
            }
        } finally {
            assertTrue(marker.delete());
            assertTrue(target.delete());
        }
    }

    @Test
    public void expiredSnapshotIsUnavailableForRestore() throws Exception {
        File snapshot = Importer.snapshotFile(RuntimeEnvironment.getApplication());
        Files.write(snapshot.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        snapshot.setLastModified(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000);
        assertFalse(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
        assertFalse(Importer.snapshotFile(RuntimeEnvironment.getApplication()).exists());
    }

    @Test
    public void tamperedSnapshotBundleDisablesRestore() throws Exception {
        createRestoreSnapshot();
        File snapshot = Importer.snapshotFile(RuntimeEnvironment.getApplication());
        Files.write(snapshot.toPath(), "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
        assertFalse(Importer.snapshotFile(RuntimeEnvironment.getApplication()).exists());
    }

    @Test
    public void snapshotBundleRejectsMismatchedMemberHash() throws Exception {
        File snapshot = Importer.snapshotFile(RuntimeEnvironment.getApplication());
        byte[] databaseBytes = Exporter.currentDatabaseBytes();
        byte[] settings = Exporter.sharedPreferencesBytes(RuntimeEnvironment.getApplication(), "Settings");
        byte[] tags = Exporter.sharedPreferencesBytes(RuntimeEnvironment.getApplication(), "ScrapedTags");
        JSONObject metadata = new JSONObject();
        metadata.put("database", Exporter.sha256(databaseBytes));
        metadata.put("settings", Exporter.sha256(settings));
        metadata.put("tags", Exporter.sha256(tags));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(snapshot))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write(databaseBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Settings.json"));
            zip.write("{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ScrapedTags.json"));
            zip.write(tags);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Manifest.json"));
            zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertFalse(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
    }

    @Test
    public void laterImportReplacesSnapshotAsOneCompleteSet() throws Exception {
        createRestoreSnapshot();
        Queries.ResumeTable.insert(123, 3);
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit().putInt("snapshotSetting", 77).commit();
        createRestoreSnapshot();
        Queries.ResumeTable.insert(123, 5);
        RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).edit().putInt("snapshotSetting", 99).commit();
        Importer.restoreLastSnapshot(RuntimeEnvironment.getApplication());
        assertEquals(3, Queries.ResumeTable.pageFromId(123));
        assertEquals(77, RuntimeEnvironment.getApplication().getSharedPreferences("Settings", 0).getInt("snapshotSetting", -1));
    }

    @Test
    public void legacySnapshotSetRemainsRestorable() throws Exception {
        byte[] databaseBytes = Exporter.currentDatabaseBytes();
        byte[] settings = Exporter.sharedPreferencesBytes(RuntimeEnvironment.getApplication(), "Settings");
        byte[] tags = Exporter.sharedPreferencesBytes(RuntimeEnvironment.getApplication(), "ScrapedTags");
        File cache = RuntimeEnvironment.getApplication().getCacheDir();
        Files.deleteIfExists(Importer.snapshotFile(RuntimeEnvironment.getApplication()).toPath());
        Files.write(new File(cache, "last-restore-snapshot.json").toPath(), databaseBytes);
        Files.write(new File(cache, "last-restore-settings.json").toPath(), settings);
        Files.write(new File(cache, "last-restore-tags.json").toPath(), tags);
        JSONObject metadata = new JSONObject();
        metadata.put("database", Exporter.sha256(databaseBytes));
        metadata.put("settings", Exporter.sha256(settings));
        metadata.put("tags", Exporter.sha256(tags));
        Files.write(new File(cache, "last-restore-snapshot.json.meta").toPath(), metadata.toString().getBytes(StandardCharsets.UTF_8));
        restore("{\"Resume\":[]}");
        assertTrue(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
        Importer.restoreLastSnapshot(RuntimeEnvironment.getApplication());
        assertEquals(7, Queries.ResumeTable.pageFromId(123));
    }

    private void createRestoreSnapshot() throws Exception {
        File backup = new File(RuntimeEnvironment.getApplication().getCacheDir(), "snapshot-source-" + System.nanoTime() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write("{\"Resume\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Importer.importData(RuntimeEnvironment.getApplication(), Uri.fromFile(backup));
        assertTrue(Importer.hasUsableSnapshot(RuntimeEnvironment.getApplication()));
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

    @Test
    public void downloadMetadataNestingIsBoundedAndFailureRollsBack() throws Exception {
        for (int depth : new int[]{63, 64}) {
            StringBuilder metadata = new StringBuilder("{\"id\":123,\"num_pages\":1,\"future\":");
            for (int index = 0; index < depth; index++) metadata.append('[');
            metadata.append("null");
            for (int index = 0; index < depth; index++) metadata.append(']');
            metadata.append('}');
            JSONObject row = new JSONObject();
            row.put("id_gallery", 123).put("range_start", 0).put("range_end", 0)
                .put("title", "test").put("metadata", metadata.toString()).put("state", "PAUSED")
                .put("failure", "NONE").put("queue_order", 1).put("metadata_attempts", 0)
                .put("page_number", -1).put("page_attempts", 0).put("retry_at", 0);
            String backup = "{\"Resume\":[],\"DownloadTasks\":[" + row + "]}";
            if (depth == 63) {
                restore(backup);
                Queries.ResumeTable.insert(123, 7);
                Queries.ReadingProgressTable.insert(123, new ReadingPosition(9, 0.5f));
            } else {
                assertThrows(IOException.class, () -> restore(backup));
                assertEquals(7, Queries.ResumeTable.pageFromId(123));
                assertEquals(9, Queries.ReadingProgressTable.fromId(123).page);
                assertFalse(database.inTransaction());
            }
        }
    }

    @Test
    public void serializedGalleryMetadataRemainsRestorable() throws Exception {
        Gallery gallery = new Gallery(null, "{\"id\":123,\"num_pages\":1,\"title\":{\"pretty\":\"Test\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
        StringWriter metadata = new StringWriter();
        gallery.jsonWrite(metadata);
        JSONObject row = new JSONObject();
        row.put("id_gallery", 123).put("range_start", 0).put("range_end", 0)
            .put("title", "test").put("metadata", metadata.toString()).put("state", "PAUSED")
            .put("failure", "NONE").put("queue_order", 1).put("metadata_attempts", 0)
            .put("page_number", -1).put("page_attempts", 0).put("retry_at", 0);
        restore("{\"DownloadTasks\":[" + row + "]}");
        try (android.database.Cursor cursor = database.rawQuery("SELECT metadata FROM DownloadTasks WHERE id_gallery=123", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(metadata.toString(), cursor.getString(0));
        }
    }
}
