// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.Database;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.async.database.DownloadTaskStore;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONObject;

public class Importer {
    private static final long SNAPSHOT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String SNAPSHOT_NAME = "last-restore-snapshot.json";
    private static final String PREVIOUS_SNAPSHOT_NAME = "previous-restore-snapshot.json";
    private static final String SETTINGS_SNAPSHOT_NAME = "last-restore-settings.json";
    private static final String TAGS_SNAPSHOT_NAME = "last-restore-tags.json";
    private static final String PREVIOUS_SETTINGS_SNAPSHOT_NAME = "previous-restore-settings.json";
    private static final String PREVIOUS_TAGS_SNAPSHOT_NAME = "previous-restore-tags.json";
    private static SharedPreferences.Editor importSharedPreferences(Context context, String sharedName, InputStream stream) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(stream));
        if (sharedName.contains("/")) {
            String[] names = sharedName.split("/");
            sharedName = names[names.length - 1];
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(sharedName, 0).edit();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            reader.beginObject();
            Exporter.SharedType type = Exporter.SharedType.valueOf(reader.nextName());
            switch (type) {
                case STRING:
                    editor.putString(name, reader.nextString());
                    break;
                case INT:
                    editor.putInt(name, reader.nextInt());
                    break;
                case FLOAT:
                    editor.putFloat(name, (float) reader.nextDouble());
                    break;
                case LONG:
                    editor.putLong(name, reader.nextLong());
                    break;
                case BOOLEAN:
                    editor.putBoolean(name, reader.nextBoolean());
                    break;
                case STRING_SET:
                    Set<String> strings = new HashSet<>();
                    reader.beginArray();
                    while (reader.hasNext())
                        strings.add(reader.nextString());
                    reader.endArray();
                    editor.putStringSet(name, strings);
                    break;
            }
            reader.endObject();
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing backup preference data");
        return editor;
    }

    static void importDB(InputStream stream) throws IOException {
        DownloadTaskStore.restore(null, () -> ReadingProgress.restore(() -> {
            importDBLocked(stream);
            Queries.StatusTable.initStatuses();
            com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidate();
        }));
    }

    private static void importDBLocked(InputStream stream) throws IOException {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null)
            throw new IOException("Can't import Database, don't have database connection yet");
        db.beginTransaction();
        try {
            JsonReader reader = new JsonReader(new InputStreamReader(stream));
            reader.beginObject();
            db.delete(Queries.ReadingProgressTable.TABLE_NAME, null, null);
            db.delete(DownloadTaskStore.TABLE_NAME, null, null);
            Set<String> seenTables = new HashSet<>();
            while (reader.hasNext()) {
                String tableName = reader.nextName();
                if (!Arrays.asList(Exporter.SCHEMAS).contains(tableName)) {
                    reader.skipValue();
                    continue;
                }
                if (!seenTables.add(tableName)) throw new IOException("Duplicate backup table");
                db.delete(tableName, null, null);
                reader.beginArray();
                while (reader.hasNext()) {
                    reader.beginObject();
                    ContentValues values = new ContentValues();
                    while (reader.hasNext()) {
                        String fieldName = reader.nextName();
                        if (tableName.equals(Queries.ReadingProgressTable.TABLE_NAME) && reader.peek() != JsonToken.NUMBER)
                            throw new IOException("Invalid reading position field type");
                        switch (reader.peek()) {
                            case NULL:
                                values.putNull(fieldName);
                                reader.nextNull();
                                break;
                            case NUMBER:
                                if (tableName.equals(Queries.ReadingProgressTable.TABLE_NAME) && fieldName.equals("offset"))
                                    values.put(fieldName, reader.nextDouble());
                                else values.put(fieldName, reader.nextLong());
                                break;
                            case STRING:
                                values.put(fieldName, reader.nextString());
                                break;
                            default:
                                throw new IOException("Unsupported backup value");
                        }
                    }
                    if (tableName.equals(Queries.ReadingProgressTable.TABLE_NAME)) validateReadingPosition(values);
                    if (tableName.equals(DownloadTaskStore.TABLE_NAME)) validateDownloadTask(values);
                    if (db.insertWithOnConflict(tableName, null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                        throw new IOException("Unable to restore backup row");
                    reader.endObject();
                }
                reader.endArray();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing database backup data");
            if (!seenTables.contains(DownloadTaskStore.TABLE_NAME)) DownloadTaskStore.migrateLegacy(db);
            db.setTransactionSuccessful();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid database backup", exception);
        } finally {
            db.endTransaction();
        }
    }

    private static void validateReadingPosition(ContentValues values) throws IOException {
        Long id = values.getAsLong("id_gallery");
        Long page = values.getAsLong("page");
        Long updatedAt = values.getAsLong("updated_at");
        Double offset = values.getAsDouble("offset");
        if (id == null || id <= 0 || id > Integer.MAX_VALUE || page == null || page <= 0 || page > Integer.MAX_VALUE
            || updatedAt == null || updatedAt < 0 || (offset != null && (!Double.isFinite(offset) || offset < 0 || offset >= 1)))
            throw new IOException("Invalid reading position values");
    }

    private static void validateDownloadTask(ContentValues values) throws IOException {
        Long id = values.getAsLong("id_gallery");
        Long start = values.getAsLong("range_start");
        Long end = values.getAsLong("range_end");
        String title = values.getAsString("title");
        String state = values.getAsString("state");
        String failure = values.getAsString("failure");
        Long order = values.getAsLong("queue_order");
        Long metadataAttempts = values.getAsLong("metadata_attempts");
        Long page = values.getAsLong("page_number");
        Long pageAttempts = values.getAsLong("page_attempts");
        Long retryAt = values.getAsLong("retry_at");
        String metadata = values.getAsString("metadata");
        String target = values.getAsString("target_path");
        String repairPages = values.getAsString("repair_pages");
        if (id == null || id <= 0 || id > Integer.MAX_VALUE || start == null || end == null
            || start < -1 || end < -1 || start >= 10000 || end >= 10000
            || (start >= 0 && end >= 0 && end < start) || title == null || state == null || failure == null
            || order == null || metadataAttempts == null || metadataAttempts < 0 || metadataAttempts > 4
            || page == null || page < -1 || page > 10000 || pageAttempts == null || pageAttempts < 0 || pageAttempts > 4
            || retryAt == null || retryAt < 0) throw new IOException("Invalid download task values");
        if (metadata != null) {
            try {
                JSONObject json = new JSONObject(metadata);
                if (json.optInt("id", -1) != id.intValue()
                    || json.optInt("num_pages", 0) <= 0 || json.optInt("num_pages", 0) > 10000)
                    throw new IOException("Invalid download task metadata");
            } catch (JSONException exception) {
                throw new IOException("Invalid download task metadata", exception);
            }
        }
        if ((target == null) != (repairPages == null)) throw new IOException("Invalid repair task");
        if (target != null) {
            if (!new java.io.File(target).isAbsolute() || repairPages.isEmpty()) throw new IOException("Invalid repair target");
            Set<Integer> pages = new HashSet<>();
            try {
                for (String value : repairPages.split(",", -1)) {
                    int pageNumber = Integer.parseInt(value);
                    if (pageNumber < 1 || pageNumber > 10000 || !pages.add(pageNumber))
                        throw new IOException("Invalid repair page");
                }
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid repair page", exception);
            }
        }
        try {
            com.maxwai.nclientv3.async.downloader.GalleryDownloaderV2.Status.valueOf(state);
            com.maxwai.nclientv3.async.downloader.DownloadFailure.valueOf(failure);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid download task state", exception);
        }
        if ("FINISHED".equals(state) || "CANCELED".equals(state))
            throw new IOException("Terminal download task cannot be restored");
    }

    public static void importData(@NonNull Context context, Uri selectedFile) throws IOException {
        DownloadTaskStore.restore(context, () -> ReadingProgress.restore(() -> importDataLocked(context, selectedFile)));
    }

    private static void importDataLocked(Context context, Uri selectedFile) throws IOException {
        SQLiteDatabase database = Database.getDatabase();
        if (database == null) throw new IOException("Database is not available");
        database.beginTransaction();
        List<SharedPreferences.Editor> preferences = new ArrayList<>();
        try {
            try (InputStream stream = context.getContentResolver().openInputStream(selectedFile);
                 ZipInputStream inputStream = new ZipInputStream(stream)) {
                ZipEntry entry;
                Set<String> seenEntries = new HashSet<>();
                boolean hasDatabase = false;
                byte[] databaseBytes = null;
                byte[] manifestBytes = null;
                while ((entry = inputStream.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!seenEntries.add(name)) throw new IOException("Duplicate backup entry");
                    if (Exporter.DB_ZIP_FILE.equals(name)) {
                        databaseBytes = readEntry(inputStream, 64 * 1024 * 1024);
                        hasDatabase = true;
                    } else if (Exporter.MANIFEST_ZIP_FILE.equals(name)) {
                        manifestBytes = readEntry(inputStream, 64 * 1024);
                    } else if (name.equals("Settings.json") || name.equals("ScrapedTags.json")) {
                        String shared = name.substring(0, name.length() - 5);
                        preferences.add(importSharedPreferences(context, shared, inputStream));
                    }
                    inputStream.closeEntry();
                }
                if (!hasDatabase) throw new IOException("Backup has no database");
                if (manifestBytes != null) validateManifest(manifestBytes, databaseBytes);
                BackupPreview.validateDatabaseBytes(databaseBytes);
                writeSnapshotCandidate(context);
                importDBLocked(new ByteArrayInputStream(databaseBytes));
            }
            database.setTransactionSuccessful();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid backup archive", exception);
        } finally {
            database.endTransaction();
        }
        promoteSnapshot(context);
        for (SharedPreferences.Editor editor : preferences) editor.apply();
        Queries.StatusTable.initStatuses();
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidate();
    }

    public static File snapshotFile(Context context) {
        return new File(context.getCacheDir(), SNAPSHOT_NAME);
    }

    public static File previousSnapshotFile(Context context) {
        return new File(context.getCacheDir(), PREVIOUS_SNAPSHOT_NAME);
    }

    public static boolean hasUsableSnapshot(Context context) {
        File snapshot = snapshotFile(context);
        boolean usable = snapshot.isFile() && snapshot.length() > 0
            && System.currentTimeMillis() - snapshot.lastModified() <= SNAPSHOT_MAX_AGE_MS;
        if (!usable && snapshot.isFile()) clearSnapshots(context);
        return usable;
    }

    public static long snapshotAgeDays(Context context) {
        if (!snapshotFile(context).isFile()) return -1;
        return Math.max(0, (System.currentTimeMillis() - snapshotFile(context).lastModified()) / (24L * 60 * 60 * 1000));
    }

    private static File settingsSnapshotFile(Context context) { return new File(context.getCacheDir(), SETTINGS_SNAPSHOT_NAME); }
    private static File tagsSnapshotFile(Context context) { return new File(context.getCacheDir(), TAGS_SNAPSHOT_NAME); }
    private static File previousSettingsSnapshotFile(Context context) { return new File(context.getCacheDir(), PREVIOUS_SETTINGS_SNAPSHOT_NAME); }
    private static File previousTagsSnapshotFile(Context context) { return new File(context.getCacheDir(), PREVIOUS_TAGS_SNAPSHOT_NAME); }

    private static void clearSnapshots(Context context) {
        File[] files = {snapshotFile(context), previousSnapshotFile(context), settingsSnapshotFile(context),
            tagsSnapshotFile(context), previousSettingsSnapshotFile(context), previousTagsSnapshotFile(context),
            new File(context.getCacheDir(), SNAPSHOT_NAME + ".part"),
            new File(context.getCacheDir(), SETTINGS_SNAPSHOT_NAME + ".part"),
            new File(context.getCacheDir(), TAGS_SNAPSHOT_NAME + ".part")};
        for (File file : files) if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private static void writeSnapshotCandidate(Context context) throws IOException {
        byte[] bytes = Exporter.currentDatabaseBytes();
        writeAtomic(new File(context.getCacheDir(), SNAPSHOT_NAME + ".part"), bytes);
        writeAtomic(new File(context.getCacheDir(), SETTINGS_SNAPSHOT_NAME + ".part"), Exporter.sharedPreferencesBytes(context, "Settings"));
        writeAtomic(new File(context.getCacheDir(), TAGS_SNAPSHOT_NAME + ".part"), Exporter.sharedPreferencesBytes(context, "ScrapedTags"));
    }

    private static void writeAtomic(File temporary, byte[] bytes) throws IOException {
        File target = new File(temporary.getPath().substring(0, temporary.getPath().length() - 5));
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void promoteSnapshotFiles(Context context) throws IOException {
        File settings = settingsSnapshotFile(context);
        File tags = tagsSnapshotFile(context);
        if (settings.isFile()) Files.copy(settings.toPath(), previousSettingsSnapshotFile(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (tags.isFile()) Files.copy(tags.toPath(), previousTagsSnapshotFile(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(new File(settings.getPath() + ".part").toPath(), settings.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(new File(tags.getPath() + ".part").toPath(), tags.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeSnapshotCandidateLegacy(Context context) throws IOException {
        byte[] bytes = Exporter.currentDatabaseBytes();
        File target = snapshotFile(context);
        File temporary = new File(target.getPath() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void promoteSnapshot(Context context) throws IOException {
        File target = snapshotFile(context);
        File temporary = new File(target.getPath() + ".part");
        if (target.isFile()) Files.copy(target.toPath(), previousSnapshotFile(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        promoteSnapshotFiles(context);
    }

    public static void restoreLastSnapshot(Context context) throws IOException {
        File snapshot = snapshotFile(context);
        if (!hasUsableSnapshot(context)) throw new IOException("No usable restore snapshot is available");
        DownloadTaskStore.restore(context, () -> ReadingProgress.restore(() -> {
            try (InputStream input = new java.io.FileInputStream(snapshot)) {
                importDBLocked(input);
            }
            if (settingsSnapshotFile(context).isFile()) {
                try (InputStream input = new java.io.FileInputStream(settingsSnapshotFile(context))) {
                    importSharedPreferences(context, "Settings", input).apply();
                }
            }
            if (tagsSnapshotFile(context).isFile()) {
                try (InputStream input = new java.io.FileInputStream(tagsSnapshotFile(context))) {
                    importSharedPreferences(context, "ScrapedTags", input).apply();
                }
            }
            Queries.StatusTable.initStatuses();
            com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidate();
        }));
    }

    private static byte[] readEntry(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Backup entry exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static void validateManifest(byte[] manifestBytes, byte[] databaseBytes) throws IOException {
        try {
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (manifest.optInt("format_version", -1) != Exporter.BACKUP_FORMAT_VERSION
                || manifest.optLong("database_length", -1) != databaseBytes.length
                || !Exporter.sha256(databaseBytes).equalsIgnoreCase(manifest.optString("database_sha256", "")))
                throw new IOException("Backup integrity or format check failed");
        } catch (JSONException exception) {
            throw new IOException("Invalid backup manifest", exception);
        }
    }
}
