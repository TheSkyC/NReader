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
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

class Importer {
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
                while ((entry = inputStream.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!seenEntries.add(name)) throw new IOException("Duplicate backup entry");
                    if (Exporter.DB_ZIP_FILE.equals(name)) {
                        importDBLocked(inputStream);
                        hasDatabase = true;
                    } else if (name.equals("Settings.json") || name.equals("ScrapedTags.json")) {
                        String shared = name.substring(0, name.length() - 5);
                        preferences.add(importSharedPreferences(context, shared, inputStream));
                    }
                    inputStream.closeEntry();
                }
                if (!hasDatabase) throw new IOException("Backup has no database");
            }
            database.setTransactionSuccessful();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid backup archive", exception);
        } finally {
            database.endTransaction();
        }
        for (SharedPreferences.Editor editor : preferences) editor.apply();
        Queries.StatusTable.initStatuses();
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidate();
    }
}
