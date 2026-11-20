package com.theskyc.nreader.async.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.theskyc.nreader.api.enums.TagType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Applies validated public directory rows without touching local or account rules. */
public final class TagCatalog {
    static final String CREATE_STATE = "CREATE TABLE IF NOT EXISTS TagCatalogState ("
        + "source TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL CHECK (revision > 0))";
    public static final int MIN_DIRECTORY_ROWS = 50_000;
    private static final String UPSTREAM = "legacy-upstream";
    private static final String BUNDLED = "bundled";
    private static final int MAX_ROWS = 250_000;
    private static final int MAX_NAME_LENGTH = 256;
    private static final String STAGE = "temp.TagCatalogStage";
    private static final MutableLiveData<Void> CHANGES = new MutableLiveData<>();

    private TagCatalog() {}

    public static int installedRevision(SQLiteDatabase database) {
        return installedRevision(database, UPSTREAM);
    }

    static int installedSeedRevision(SQLiteDatabase database) {
        return installedRevision(database, BUNDLED);
    }

    private static int installedRevision(SQLiteDatabase database, String source) {
        try (Cursor row = database.rawQuery("SELECT revision FROM TagCatalogState WHERE source = ?",
            new String[]{source})) {
            return row.moveToFirst() ? row.getInt(0) : 0;
        }
    }

    static void upgradeState(SQLiteDatabase database) {
        boolean legacyKey = false;
        try (Cursor columns = database.rawQuery("PRAGMA table_info(TagCatalogState)", null)) {
            int name = columns.getColumnIndexOrThrow("name");
            while (columns.moveToNext()) {
                if ("id".equals(columns.getString(name))) {
                    legacyKey = true;
                    break;
                }
            }
        }
        if (legacyKey) database.execSQL("ALTER TABLE TagCatalogState RENAME TO OldTagCatalogState");
        database.execSQL(CREATE_STATE);
        if (!legacyKey) return;
        database.execSQL("INSERT INTO TagCatalogState (source, revision)"
            + " SELECT ?, revision FROM OldTagCatalogState WHERE id = 1", new Object[]{UPSTREAM});
        database.execSQL("DROP TABLE OldTagCatalogState");
    }

    public static void invalidateRevision(SQLiteDatabase database) {
        database.delete("TagCatalogState", null, null);
    }

    public static LiveData<Void> changes() {
        return CHANGES;
    }

    public static int readRevision(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        int next;
        while ((next = input.read()) != -1) {
            if (bytes.size() >= 32) throw new IOException("Tag catalog revision exceeds limit");
            bytes.write(next);
        }
        String value = new String(bytes.toByteArray(), StandardCharsets.US_ASCII).trim();
        try {
            if (value.matches("[1-9][0-9]*")) return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            // Report a bounded error without logging the value supplied by the feed.
        }
        throw new IOException("Invalid tag catalog revision");
    }

    public static boolean needsBundled(SQLiteDatabase database, int revision) {
        return installedRevision(database) == 0 && revision > installedSeedRevision(database);
    }

    public static boolean applyBundled(SQLiteDatabase database, InputStream source, int revision,
                                       int minimumRows, BooleanSupplier cancelled) throws IOException {
        return apply(database, source, revision, minimumRows, cancelled, BUNDLED);
    }

    public static boolean apply(SQLiteDatabase database, InputStream source, int revision,
                                int minimumRows, BooleanSupplier cancelled) throws IOException {
        return apply(database, source, revision, minimumRows, cancelled, UPSTREAM);
    }

    private static boolean apply(SQLiteDatabase database, InputStream source, int revision,
                                 int minimumRows, BooleanSupplier cancelled, String dataset) throws IOException {
        if (revision < 1 || minimumRows < 1 || minimumRows > MAX_ROWS) {
            throw new IllegalArgumentException("Invalid tag catalog revision or row limit");
        }
        database.beginTransactionNonExclusive();
        try {
            if (BUNDLED.equals(dataset) ? !needsBundled(database, revision)
                : revision <= installedRevision(database, dataset)) return false;
            database.execSQL("CREATE TEMP TABLE TagCatalogStage (idTag INTEGER PRIMARY KEY,"
                + " name TEXT NOT NULL, count INTEGER NOT NULL, type INTEGER NOT NULL)");
            loadStage(database, source, minimumRows, cancelled);
            try (Cursor conflict = database.rawQuery("SELECT 1 FROM " + STAGE
                + " AS incoming JOIN Tags ON Tags.idTag = incoming.idTag"
                + " WHERE Tags.type != incoming.type LIMIT 1", null)) {
                if (conflict.moveToFirst()) throw new IOException("Tag catalog type conflict");
            }
            checkCancelled(cancelled);
            database.execSQL("UPDATE Tags SET name = (SELECT name FROM " + STAGE
                + " WHERE idTag = Tags.idTag), count = (SELECT count FROM " + STAGE
                + " WHERE idTag = Tags.idTag) WHERE idTag IN (SELECT idTag FROM " + STAGE + ")");
            database.execSQL("INSERT INTO Tags (idTag, name, count, type, status)"
                + " SELECT idTag, name, count, type, 0 FROM " + STAGE
                + " AS incoming WHERE NOT EXISTS (SELECT 1 FROM Tags WHERE Tags.idTag = incoming.idTag)");
            database.execSQL("INSERT OR REPLACE INTO TagCatalogState (source, revision) VALUES (?, ?)",
                new Object[]{dataset, revision});
            database.execSQL("DROP TABLE " + STAGE);
            checkCancelled(cancelled);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        CHANGES.postValue(null);
        return true;
    }

    private static void loadStage(SQLiteDatabase database, InputStream source, int minimumRows,
                                  BooleanSupplier cancelled) throws IOException {
        int rows = 0;
        int typeMask = 0;
        try (JsonReader reader = new JsonReader(new JsonStringGuard(new InputStreamReader(source,
                 StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))));
             SQLiteStatement insert = database.compileStatement("INSERT OR IGNORE INTO " + STAGE
                 + " (idTag, name, count, type) VALUES (?, ?, ?, ?)")) {
            reader.beginArray();
            while (reader.hasNext()) {
                checkCancelled(cancelled);
                if (++rows > MAX_ROWS) throw new IOException("Tag catalog has too many rows");
                reader.beginArray();
                int id = readInteger(reader, 1, Integer.MAX_VALUE);
                String name = readName(reader);
                int count = readInteger(reader, 0, Integer.MAX_VALUE);
                int type = readInteger(reader, 1, TagType.values.length - 1);
                reader.endArray();
                typeMask |= 1 << type;
                insert.bindLong(1, id);
                insert.bindString(2, name);
                insert.bindLong(3, count);
                insert.bindLong(4, type);
                if (insert.executeInsert() == -1) throw new IOException("Duplicate tag ID");
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT
                || rows < minimumRows || typeMask != (1 << TagType.values.length) - 2) {
                throw new IOException("Incomplete tag catalog");
            }
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Invalid tag catalog format", error);
        }
    }

    private static int readInteger(JsonReader reader, int min, int max) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected tag catalog integer");
        int value = Integer.parseInt(reader.nextString());
        if (value < min || value > max) throw new IOException("Tag catalog integer out of range");
        return value;
    }

    private static String readName(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected tag name");
        String name = reader.nextString();
        if (name.length() > MAX_NAME_LENGTH || name.trim().isEmpty()
            || name.indexOf('"') >= 0 || name.indexOf('\\') >= 0
            || name.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.SURROGATE)) {
            throw new IOException("Invalid tag name");
        }
        return name;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Tag catalog update stopped");
        }
    }

    // Android's JsonReader accepts unknown string escapes even with strict mode enabled.
    private static class JsonStringGuard extends Reader {
        private final Reader source;
        private boolean inString;
        private boolean escaped;
        private int hexDigitsRemaining;
        private int stringLength;

        JsonStringGuard(Reader source) {
            this.source = source;
        }

        @Override
        public int read(char[] buffer, int start, int length) throws IOException {
            int count = source.read(buffer, start, length);
            for (int index = start; index < start + count; index++) {
                char character = buffer[index];
                if (!inString) {
                    inString = character == '"';
                    stringLength = 0;
                } else if (hexDigitsRemaining > 0) {
                    if ("0123456789abcdefABCDEF".indexOf(character) < 0) throw new IOException("Invalid JSON escape");
                    hexDigitsRemaining--;
                } else if (escaped) {
                    if ("\"\\/bfnrtu".indexOf(character) < 0) throw new IOException("Invalid JSON escape");
                    if (character == 'u') hexDigitsRemaining = 4;
                    escaped = false;
                } else if (character == '"') {
                    inString = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character < 0x20) {
                    throw new IOException("Unescaped JSON control character");
                }
                if (inString && ++stringLength > MAX_NAME_LENGTH * 6 + 1) {
                    throw new IOException("Tag catalog string exceeds limit");
                }
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
