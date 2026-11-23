// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.format.DateFormat;
import android.util.JsonWriter;

import com.theskyc.nreader.SettingsActivity;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.DownloadTaskStore;
import com.theskyc.nreader.async.database.SearchPlanStore;
import com.theskyc.nreader.async.database.TagViewStore;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Exporter {
    static final String DB_ZIP_FILE = "Database.json";
    static final String MANIFEST_ZIP_FILE = "Manifest.json";
    static final int BACKUP_FORMAT_VERSION = 4;
    private static final String[] SHARED_FILES = new String[]{
        "Settings",
        "ScrapedTags",
    };
    static final String[] SCHEMAS = new String[]{
        Queries.GalleryTable.TABLE_NAME,
        BackupTags.RULES,
        BackupTags.REFERENCES,
        Queries.GalleryBridgeTable.TABLE_NAME,
        Queries.BookmarkTable.TABLE_NAME,
        SearchPlanStore.TABLE_NAME,
        TagViewStore.TABLE_NAME,
        Queries.DownloadTable.TABLE_NAME,
        DownloadTaskStore.TABLE_NAME,
        Queries.HistoryTable.TABLE_NAME,
        Queries.FavoriteTable.TABLE_NAME,
        Queries.ResumeTable.TABLE_NAME,
        Queries.ReaderBookmarkTable.TABLE_NAME,
        Queries.ReadingProgressTable.TABLE_NAME,
        Queries.StatusTable.TABLE_NAME,
        Queries.StatusMangaTable.TABLE_NAME,
    };

    private static void dumpDB(OutputStream stream) throws IOException {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null)
            throw new IOException("Can't export Database, don't have database connection yet");
        boolean ownTransaction = !db.inTransaction();
        if (ownTransaction) db.beginTransactionNonExclusive();
        try {
            dumpTables(db, stream);
            if (ownTransaction) db.setTransactionSuccessful();
        } finally {
            if (ownTransaction) db.endTransaction();
        }
    }

    private static void dumpTables(SQLiteDatabase db, OutputStream stream) throws IOException {
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        writer.beginObject();
        for (String s : SCHEMAS) {
            try (Cursor cur = BackupTags.exportRows(db, s)) {
                writer.name(s).beginArray();
                if (cur.moveToFirst()) {
                    do {
                        writer.beginObject();
                        for (int i = 0; i < cur.getColumnCount(); i++) {
                            String column = cur.getColumnName(i);
                            writer.name(column);
                            if (cur.isNull(i)) {
                                writer.nullValue();
                            } else {
                                switch (cur.getType(i)) {
                                    case Cursor.FIELD_TYPE_INTEGER:
                                        writer.value(cur.getLong(i));
                                        break;
                                    case Cursor.FIELD_TYPE_FLOAT:
                                        writer.value(cur.getDouble(i));
                                        break;
                                    case Cursor.FIELD_TYPE_STRING:
                                        String value = cur.getString(i);
                                        writer.value(BackupTags.isTagSection(s) && "name".equals(column)
                                            && !TagCatalog.isPortableLabel(value) ? "" : value);
                                        break;
                                    case Cursor.FIELD_TYPE_BLOB:
                                    case Cursor.FIELD_TYPE_NULL:
                                        break;
                                }
                            }
                        }
                        writer.endObject();
                    } while (cur.moveToNext());
                }
                writer.endArray();
            }
        }
        writer.endObject();
        writer.flush();
    }

    public static String defaultExportName(SettingsActivity context) {
        Date actualTime = new Date();
        String date = DateFormat.getDateFormat(context).format(actualTime).replaceAll("[^0-9]*", "");
        String time = DateFormat.getTimeFormat(context).format(actualTime).replaceAll("[^0-9]*", "");
        return String.format("Backup_%s_%s.zip", date, time);
    }

    public static void exportData(Context context, Uri selectedFile) throws IOException {

        OutputStream outputStream = context.getContentResolver().openOutputStream(selectedFile);
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);

            ByteArrayOutputStream database = new ByteArrayOutputStream();
            dumpDB(database);
            byte[] databaseBytes = database.toByteArray();
            zip.putNextEntry(new ZipEntry(MANIFEST_ZIP_FILE));
            JsonWriter manifest = new JsonWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            manifest.beginObject().name("format_version").value(BACKUP_FORMAT_VERSION)
                .name("database_length").value(databaseBytes.length)
                .name("database_sha256").value(sha256(databaseBytes)).endObject().flush();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DB_ZIP_FILE));
            zip.write(databaseBytes);
            zip.closeEntry();

            for (String shared : SHARED_FILES) {
                zip.putNextEntry(new ZipEntry(shared + ".json"));
                exportSharedPreferences(context, shared, zip);
                zip.closeEntry();
            }
        }

    }

    static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception exception) {
            throw new IOException("Unable to hash backup data", exception);
        }
    }

    static byte[] currentDatabaseBytes() throws IOException {
        ByteArrayOutputStream database = new ByteArrayOutputStream();
        dumpDB(database);
        return database.toByteArray();
    }

    static byte[] sharedPreferencesBytes(Context context, String sharedName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportSharedPreferences(context, sharedName, output);
        return output.toByteArray();
    }

    private static void exportSharedPreferences(Context context, String sharedName, OutputStream stream) throws IOException {
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(stream));
        SharedPreferences pref = context.getSharedPreferences(sharedName, 0);
        Map<String, ?> map = pref.getAll();
        writer.beginObject();
        for (Map.Entry<String, ?> o : map.entrySet()) {
            Object val = o.getValue();
            writer.name(o.getKey());
            if (val instanceof String) {
                writer.beginObject().name(SharedType.STRING.name()).value((String) val).endObject();
            } else if (val instanceof Boolean) {
                writer.beginObject().name(SharedType.BOOLEAN.name()).value((Boolean) val).endObject();
            } else if (val instanceof Integer) {
                writer.beginObject().name(SharedType.INT.name()).value((Integer) val).endObject();
            } else if (val instanceof Float) {
                writer.beginObject().name(SharedType.FLOAT.name()).value((Float) val).endObject();
            } else if (val instanceof Set) {
                writer.beginObject().name(SharedType.STRING_SET.name());
                writer.beginArray();
                Set<?> val2 = (Set<?>) val;
                if (!val2.isEmpty()) {
                    for (Object s : (Set<?>) val) {
                        if (s instanceof String) {
                            writer.value((String) s);
                        } else {
                            LogUtility.e("Missing export class: " + val.getClass().getName());
                        }
                    }
                }
                writer.endArray();
                writer.endObject();
            } else if (val instanceof Long) {
                writer.beginObject().name(SharedType.LONG.name()).value((Long) val).endObject();
            } else {
                LogUtility.e("Missing export class: " + val.getClass().getName());
            }
        }
        writer.endObject();
        writer.flush();
    }

    enum SharedType {
        FLOAT, INT, LONG, STRING_SET, STRING, BOOLEAN
    }


}
