package com.theskyc.nreader.async.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.utility.JsonStringGuard;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Installs one locale's public tag names and aliases atomically. */
public final class TagTranslationCatalog {
    static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS TagTranslations ("
        + "idTag INTEGER NOT NULL, locale TEXT NOT NULL, displayName TEXT NOT NULL, displayKey TEXT NOT NULL,"
        + " PRIMARY KEY (idTag, locale))";
    private static final String CREATE_ALIASES = "CREATE TABLE IF NOT EXISTS TagTranslationAliases ("
        + "idTag INTEGER NOT NULL, locale TEXT NOT NULL, alias TEXT NOT NULL, aliasKey TEXT NOT NULL,"
        + " PRIMARY KEY (idTag, locale, aliasKey))";
    private static final String CREATE_STATE = "CREATE TABLE IF NOT EXISTS TagTranslationState ("
        + "locale TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL CHECK (revision > 0))";
    private static final String CREATE_DISPLAY_INDEX = "CREATE INDEX IF NOT EXISTS TagTranslations_locale_key_idx"
        + " ON TagTranslations (locale, displayKey, idTag)";
    private static final String CREATE_ALIAS_INDEX = "CREATE INDEX IF NOT EXISTS TagTranslationAliases_locale_key_idx"
        + " ON TagTranslationAliases (locale, aliasKey, idTag)";
    private static final String ENTRY_STAGE = "temp.TagTranslationEntryStage";
    private static final String ALIAS_STAGE = "temp.TagTranslationAliasStage";
    private static final int MAX_PACK_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 250_000;
    private static final int MAX_TEXT_CODE_POINTS = 256;
    private static final int MAX_ALIASES_PER_ENTRY = 20;

    private TagTranslationCatalog() {}

    static void createTables(SQLiteDatabase database) {
        database.execSQL(CREATE_TABLE);
        database.execSQL(CREATE_ALIASES);
        database.execSQL(CREATE_STATE);
        database.execSQL(CREATE_DISPLAY_INDEX);
        database.execSQL(CREATE_ALIAS_INDEX);
    }

    public static int installedRevision(SQLiteDatabase database, String locale) {
        String canonicalLocale = validateLocale(locale);
        try (Cursor cursor = database.rawQuery("SELECT revision FROM TagTranslationState WHERE locale = ?",
            new String[]{canonicalLocale})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public static boolean apply(SQLiteDatabase database, InputStream input, BooleanSupplier cancelled) throws IOException {
        database.beginTransactionNonExclusive();
        try {
            database.execSQL("CREATE TEMP TABLE TagTranslationEntryStage ("
                + "idTag INTEGER PRIMARY KEY, displayName TEXT NOT NULL, displayKey TEXT NOT NULL)");
            database.execSQL("CREATE TEMP TABLE TagTranslationAliasStage ("
                + "idTag INTEGER NOT NULL, alias TEXT NOT NULL, aliasKey TEXT NOT NULL,"
                + " PRIMARY KEY (idTag, aliasKey))");

            PackMetadata metadata;
            try (JsonReader reader = JsonStringGuard.open(new BoundedInputStream(input, cancelled), MAX_TEXT_CODE_POINTS * 6 + 1);
                 SQLiteStatement insertEntry = database.compileStatement("INSERT INTO " + ENTRY_STAGE
                     + " (idTag, displayName, displayKey) VALUES (?, ?, ?)");
                 SQLiteStatement insertAlias = database.compileStatement("INSERT INTO " + ALIAS_STAGE
                     + " (idTag, alias, aliasKey) VALUES (?, ?, ?)")) {
                metadata = readPack(reader, insertEntry, insertAlias, cancelled);
            } catch (IllegalArgumentException | IllegalStateException | android.database.SQLException error) {
                throw new IOException("Invalid translation pack", error);
            }

            try (Cursor unknown = database.rawQuery("SELECT 1 FROM " + ENTRY_STAGE
                + " e LEFT JOIN Tags t ON t.idTag = e.idTag WHERE t.idTag IS NULL LIMIT 1", null)) {
                if (unknown.moveToFirst()) throw new IOException("Translation pack references an unknown tag ID");
            }
            if (metadata.revision <= installedRevision(database, metadata.locale)) return false;

            database.execSQL("DELETE FROM TagTranslationAliases WHERE locale = ?", new Object[]{metadata.locale});
            database.execSQL("DELETE FROM TagTranslations WHERE locale = ?", new Object[]{metadata.locale});
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " SELECT idTag, ?, displayName, displayKey FROM " + ENTRY_STAGE, new Object[]{metadata.locale});
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " SELECT idTag, ?, alias, aliasKey FROM " + ALIAS_STAGE, new Object[]{metadata.locale});
            database.execSQL("INSERT OR REPLACE INTO TagTranslationState (locale, revision) VALUES (?, ?)",
                new Object[]{metadata.locale, metadata.revision});
            database.execSQL("DROP TABLE " + ENTRY_STAGE);
            database.execSQL("DROP TABLE " + ALIAS_STAGE);
            checkCancelled(cancelled);
            database.setTransactionSuccessful();
            return true;
        } finally {
            database.endTransaction();
        }
    }

    public static void remove(SQLiteDatabase database, String locale) {
        String canonicalLocale = validateLocale(locale);
        database.beginTransactionNonExclusive();
        try {
            database.delete("TagTranslationAliases", "locale = ?", new String[]{canonicalLocale});
            database.delete("TagTranslations", "locale = ?", new String[]{canonicalLocale});
            database.delete("TagTranslationState", "locale = ?", new String[]{canonicalLocale});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static PackMetadata readPack(JsonReader reader, SQLiteStatement insertEntry,
                                         SQLiteStatement insertAlias, BooleanSupplier cancelled) throws IOException {
        Set<String> fields = new HashSet<>();
        int schemaVersion = 0;
        int revision = 0;
        String locale = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw new IOException("Duplicate translation pack field");
            switch (field) {
                case "schemaVersion": schemaVersion = readInteger(reader, 1, 1); break;
                case "revision": revision = readInteger(reader, 1, Integer.MAX_VALUE); break;
                case "locale":
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Expected translation locale");
                    locale = validateLocale(reader.nextString());
                    break;
                case "entries":
                    readEntries(reader, insertEntry, insertAlias, cancelled);
                    break;
                default: throw new IOException("Unsupported translation pack field");
            }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT || schemaVersion != 1 || revision == 0 || locale == null
            || !fields.contains("entries")) throw new IOException("Invalid translation pack header");
        return new PackMetadata(locale, revision);
    }

    private static int readEntries(JsonReader reader, SQLiteStatement insertEntry,
                                   SQLiteStatement insertAlias, BooleanSupplier cancelled) throws IOException {
        int count = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            checkCancelled(cancelled);
            if (++count > MAX_ENTRIES) throw new IOException("Translation pack has too many entries");
            Set<String> fields = new HashSet<>();
            int id = 0;
            String name = null;
            List<String> aliases = new ArrayList<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw new IOException("Duplicate translation entry field");
                switch (field) {
                    case "id": id = readInteger(reader, 1, Integer.MAX_VALUE); break;
                    case "name":
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected translated tag name");
                        name = validateText(reader.nextString());
                        break;
                    case "aliases": aliases = readAliases(reader); break;
                    default: throw new IOException("Unsupported translation entry field");
                }
            }
            reader.endObject();
            if (id == 0 || name == null) throw new IOException("Incomplete translation entry");
            insertEntry.clearBindings();
            insertEntry.bindLong(1, id);
            insertEntry.bindString(2, name);
            insertEntry.bindString(3, searchKey(name));
            insertEntry.executeInsert();
            Set<String> seenAliases = new HashSet<>();
            for (String alias : aliases) {
                String key = searchKey(alias);
                if (!seenAliases.add(key)) throw new IOException("Duplicate normalized translation alias");
                insertAlias.clearBindings();
                insertAlias.bindLong(1, id);
                insertAlias.bindString(2, alias);
                insertAlias.bindString(3, key);
                insertAlias.executeInsert();
            }
        }
        reader.endArray();
        return count;
    }

    private static List<String> readAliases(JsonReader reader) throws IOException {
        List<String> aliases = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (aliases.size() >= MAX_ALIASES_PER_ENTRY || reader.peek() != JsonToken.STRING) {
                throw new IOException("Invalid translation alias list");
            }
            aliases.add(validateText(reader.nextString()));
        }
        reader.endArray();
        return aliases;
    }

    private static int readInteger(JsonReader reader, int minimum, int maximum) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected translation pack integer");
        try {
            long value = reader.nextLong();
            if (value < minimum || value > maximum) throw new IOException("Translation pack integer is out of range");
            return (int) value;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid translation pack integer", error);
        }
    }

    private static String validateLocale(String localeTag) {
        if (localeTag == null || !localeTag.matches("[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?")) {
            throw new IllegalArgumentException("Invalid translation locale");
        }
        Locale locale = Locale.forLanguageTag(localeTag);
        if (locale.getLanguage().isEmpty() || "und".equals(locale.getLanguage())
            || !locale.toLanguageTag().equals(localeTag)) {
            throw new IllegalArgumentException("Invalid translation locale");
        }
        return localeTag;
    }

    private static String validateText(String value) throws IOException {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints == 0 || codePoints > MAX_TEXT_CODE_POINTS) throw new IOException("Invalid translation text length");
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) throw new IOException("Translation text contains control characters");
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static String searchKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new InterruptedIOException("Translation pack installation cancelled");
        }
    }

    private static final class PackMetadata {
        final String locale;
        final int revision;

        PackMetadata(String locale, int revision) {
            this.locale = locale;
            this.revision = revision;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final BooleanSupplier cancelled;
        private int total;

        BoundedInputStream(InputStream input, BooleanSupplier cancelled) {
            super(input);
            this.cancelled = cancelled;
        }

        @Override
        public int read() throws IOException {
            checkCancelled(cancelled);
            int value = super.read();
            if (value != -1 && ++total > MAX_PACK_BYTES) throw new IOException("Translation pack exceeds size limit");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkCancelled(cancelled);
            int count = super.read(buffer, offset, Math.min(length, MAX_PACK_BYTES - total + 1));
            if (count > 0) {
                total += count;
                if (total > MAX_PACK_BYTES) throw new IOException("Translation pack exceeds size limit");
            }
            return count;
        }
    }
}
