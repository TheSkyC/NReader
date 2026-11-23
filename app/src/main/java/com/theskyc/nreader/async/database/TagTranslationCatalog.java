package com.theskyc.nreader.async.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.theskyc.nreader.utility.JsonStringGuard;
import com.theskyc.nreader.utility.LogUtility;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Installs one locale's public tag names and aliases atomically. */
public final class TagTranslationCatalog {
    private static final MutableLiveData<Void> CHANGES = new MutableLiveData<>();
    private static final int DISPLAY_LOOKUP_BATCH_SIZE = 256;
    private static final int INSERT_BATCH_SIZE = 128;
    static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS TagTranslations ("
        + "idTag INTEGER NOT NULL, locale TEXT NOT NULL, displayName TEXT NOT NULL, displayKey TEXT NOT NULL,"
        + " PRIMARY KEY (idTag, locale))";
    private static final String CREATE_ALIASES = "CREATE TABLE IF NOT EXISTS TagTranslationAliases ("
        + "idTag INTEGER NOT NULL, locale TEXT NOT NULL, alias TEXT NOT NULL, aliasKey TEXT NOT NULL,"
        + " aliasOrder INTEGER NOT NULL DEFAULT -1 CHECK (aliasOrder >= -1),"
        + " aliasId INTEGER PRIMARY KEY)";
    private static final String CREATE_STATE = "CREATE TABLE IF NOT EXISTS TagTranslationState ("
        + "locale TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL CHECK (revision > 0))";
    private static final String CREATE_GENERATIONS = "CREATE TABLE IF NOT EXISTS TagTranslationGenerations ("
        + "locale TEXT PRIMARY KEY NOT NULL, generation INTEGER NOT NULL CHECK (generation > 0))";
    private static final String CREATE_DISPLAY_INDEX = "CREATE INDEX IF NOT EXISTS TagTranslations_locale_key_idx"
        + " ON TagTranslations (locale, displayKey, idTag)";
    private static final String CREATE_ALIAS_INDEX = "CREATE INDEX IF NOT EXISTS TagTranslationAliases_locale_key_idx"
        + " ON TagTranslationAliases (locale, aliasKey, idTag)";
    private static final String CREATE_ALIAS_TAG_INDEX = "CREATE INDEX IF NOT EXISTS TagTranslationAliases_tag_order_idx"
        + " ON TagTranslationAliases (idTag, locale, aliasOrder)";
    static final int MAX_PACK_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PREPARED_BYTES = 8 * MAX_PACK_BYTES;
    private static final int MAX_ENTRIES = 250_000;
    private static final int MAX_TEXT_CODE_POINTS = 256;
    private static final int MAX_ALIASES_PER_ENTRY = 20;
    private static final String FINAL_SIGMA = "\u03c2";
    private static final String SIGMA = "\u03c3";

    private TagTranslationCatalog() {}

    public static final class UnknownTagException extends IOException {
        private UnknownTagException() {
            super("Translation pack references an unknown tag ID");
        }
    }

    public static LiveData<Void> changes() {
        return CHANGES;
    }

    static Map<Integer, String> getDisplayNames(SQLiteDatabase database, Collection<Integer> ids, Locale appLocale) {
        if (ids.isEmpty()) return Collections.emptyMap();
        String locale = resolveInstalledLocale(database, appLocale);
        if (locale == null) return Collections.emptyMap();

        List<Integer> siteIds = new ArrayList<>(new HashSet<>(ids));
        siteIds.removeIf(id -> id == null || id <= 0);
        Map<Integer, String> names = new HashMap<>();
        for (int start = 0; start < siteIds.size(); start += DISPLAY_LOOKUP_BATCH_SIZE) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyMap();
            int count = Math.min(DISPLAY_LOOKUP_BATCH_SIZE, siteIds.size() - start);
            String[] args = new String[count + 1];
            args[0] = locale;
            for (int i = 0; i < count; i++) args[i + 1] = Integer.toString(siteIds.get(start + i));
            String placeholders = String.join(",", Collections.nCopies(count, "?"));
            try (Cursor cursor = database.rawQuery("SELECT idTag, displayName FROM TagTranslations"
                + " WHERE locale = ? AND idTag IN (" + placeholders + ")", args)) {
                while (cursor.moveToNext()) names.put(cursor.getInt(0), cursor.getString(1));
            }
        }
        return names;
    }

    static void createTables(SQLiteDatabase database) {
        database.execSQL(CREATE_TABLE);
        database.execSQL(CREATE_ALIASES);
        database.execSQL(CREATE_STATE);
        createGenerationTable(database);
        database.execSQL(CREATE_DISPLAY_INDEX);
        database.execSQL(CREATE_ALIAS_INDEX);
        database.execSQL(CREATE_ALIAS_TAG_INDEX);
    }

    static void createGenerationTable(SQLiteDatabase database) {
        database.execSQL(CREATE_GENERATIONS);
        database.execSQL("CREATE INDEX IF NOT EXISTS TagTranslationGenerations_generation_idx"
            + " ON TagTranslationGenerations (generation)");
    }

    static void upgradeAliasOrder(SQLiteDatabase database) {
        database.execSQL("ALTER TABLE TagTranslationAliases ADD COLUMN aliasOrder INTEGER NOT NULL"
            + " DEFAULT -1 CHECK (aliasOrder >= -1)");
    }

    static void upgradeAliasIdentity(SQLiteDatabase database) {
        database.execSQL("ALTER TABLE TagTranslationAliases RENAME TO TagTranslationAliases_v26");
        database.execSQL(CREATE_ALIASES);
        database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
            + " SELECT idTag, locale, alias, aliasKey, aliasOrder FROM TagTranslationAliases_v26");
        database.execSQL("DROP TABLE TagTranslationAliases_v26");
        database.execSQL(CREATE_ALIAS_INDEX);
        database.execSQL(CREATE_ALIAS_TAG_INDEX);
    }

    public static long generationSnapshot(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(MAX(generation), 0) FROM TagTranslationGenerations", null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private static long localeGeneration(SQLiteDatabase database, String locale) {
        try (Cursor cursor = database.rawQuery("SELECT generation FROM TagTranslationGenerations WHERE locale = ?",
            new String[]{locale})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static void advanceGeneration(SQLiteDatabase database, String locale) {
        long current = generationSnapshot(database);
        if (current == Long.MAX_VALUE) throw new IllegalStateException("Translation generation exhausted");
        database.execSQL("INSERT OR REPLACE INTO TagTranslationGenerations (locale, generation) VALUES (?, ?)",
            new Object[]{locale, current + 1});
    }

    public static final class PackState {
        private final String locale;
        public final int revision;
        public final long generation;

        private PackState(String locale, int revision, long generation) {
            this.locale = locale;
            this.revision = revision;
            this.generation = generation;
        }
    }

    public static Map<String, PackState> installedPackStates(SQLiteDatabase database) {
        Map<String, PackState> packs = new TreeMap<>();
        try (Cursor cursor = database.rawQuery("SELECT s.locale, s.revision, COALESCE(g.generation, 0)"
            + " FROM TagTranslationState s LEFT JOIN TagTranslationGenerations g ON g.locale = s.locale", null)) {
            while (cursor.moveToNext()) {
                String locale = cursor.getString(0);
                packs.put(locale, new PackState(locale, cursor.getInt(1), cursor.getLong(2)));
            }
        }
        return packs;
    }

    public static final class UnknownAliasOrderException extends IOException {
        private UnknownAliasOrderException() {
            super("Installed translation alias order is unknown");
        }
    }

    public static final class ChangedDuringExportException extends IOException {
        private ChangedDuringExportException() {
            super("Translation pack changed before export");
        }
    }

    /** Returns a rewound app-private snapshot; the caller must close the channel. */
    public static SeekableByteChannel exportSnapshot(SQLiteDatabase database, PackState expected,
                                                      File scratchDirectory, BooleanSupplier cancelled) throws IOException {
        return exportSnapshot(database, expected, scratchDirectory, cancelled, MAX_PACK_BYTES);
    }

    static SeekableByteChannel exportSnapshot(SQLiteDatabase database, PackState expected,
                                               File scratchDirectory, BooleanSupplier cancelled, int byteLimit) throws IOException {
        if (expected == null || byteLimit < 1 || byteLimit > MAX_PACK_BYTES) {
            throw new IllegalArgumentException("Invalid translation export request");
        }
        SeekableByteChannel scratch = openScratch(scratchDirectory);
        try {
            database.beginTransactionNonExclusive();
            try {
                checkCancelled(cancelled);
                if (installedRevision(database, expected.locale) != expected.revision
                    || localeGeneration(database, expected.locale) != expected.generation) {
                    throw new ChangedDuringExportException();
                }
                JsonWriter writer = new JsonWriter(new OutputStreamWriter(new BufferedOutputStream(
                    new ExportOutputStream(Channels.newOutputStream(scratch), byteLimit, cancelled)), StandardCharsets.UTF_8));
                writer.beginObject();
                writer.name("schemaVersion").value(1);
                writer.name("locale").value(expected.locale);
                writer.name("revision").value(expected.revision);
                writer.name("entries").beginArray();
                int previousId = 0;
                int entries = 0;
                int nextAliasOrder = 0;
                try (Cursor rows = database.rawQuery("SELECT t.idTag, t.displayName, a.alias, a.aliasOrder"
                    + " FROM TagTranslations t LEFT JOIN TagTranslationAliases a"
                    + " ON a.idTag = t.idTag AND a.locale = t.locale"
                    + " WHERE t.locale = ? ORDER BY t.idTag, a.aliasOrder", new String[]{expected.locale})) {
                    while (rows.moveToNext()) {
                        checkCancelled(cancelled);
                        int id = rows.getInt(0);
                        if (id <= 0) throw new IOException("Invalid translation tag ID");
                        if (id != previousId) {
                            if (id <= previousId || ++entries > MAX_ENTRIES) throw new IOException("Invalid translation entries");
                            if (previousId != 0) {
                                writer.endArray();
                                writer.endObject();
                            }
                            String name = rows.getString(1);
                            if (!validateText(name).equals(name)) throw new IOException("Invalid installed translation name");
                            writer.beginObject();
                            writer.name("id").value(id);
                            writer.name("name").value(name);
                            writer.name("aliases").beginArray();
                            previousId = id;
                            nextAliasOrder = 0;
                        }
                        if (!rows.isNull(2)) {
                            if (rows.getInt(3) != nextAliasOrder || nextAliasOrder >= MAX_ALIASES_PER_ENTRY) {
                                throw new UnknownAliasOrderException();
                            }
                            String alias = rows.getString(2);
                            if (!validateText(alias).equals(alias)) throw new IOException("Invalid installed translation alias");
                            writer.value(alias);
                            nextAliasOrder++;
                        }
                    }
                }
                if (entries == 0) throw new IOException("Empty installed translation pack");
                writer.endArray();
                writer.endObject();
                writer.endArray();
                writer.endObject();
                writer.flush();
                checkCancelled(cancelled);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            scratch.position(0);
            return scratch;
        } catch (IOException | RuntimeException | Error error) {
            try {
                scratch.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private static final class ExportOutputStream extends FilterOutputStream {
        private final int limit;
        private final BooleanSupplier cancelled;
        private int total;

        ExportOutputStream(OutputStream output, int limit, BooleanSupplier cancelled) {
            super(output);
            this.limit = limit;
            this.cancelled = cancelled;
        }

        @Override public void write(int value) throws IOException {
            checkCancelled(cancelled);
            if (total == limit) throw new IOException("Translation export exceeds size limit");
            out.write(value);
            total++;
        }

        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            checkCancelled(cancelled);
            if (length > limit - total) throw new IOException("Translation export exceeds size limit");
            out.write(buffer, offset, length);
            total += length;
        }
    }

    public static final class ChangedDuringImportException extends IOException {
        private ChangedDuringImportException() {
            super("Translation pack changed during import");
        }
    }

    static void upgradeSearchKeys(SQLiteDatabase database) {
        Object[] sigmaReplacement = {FINAL_SIGMA, SIGMA, FINAL_SIGMA};
        database.execSQL("UPDATE TagTranslations SET displayKey = replace(displayKey, ?, ?)"
            + " WHERE instr(displayKey, ?) > 0", sigmaReplacement);
        try (Cursor aliases = database.rawQuery(
            "SELECT 1 FROM TagTranslationAliases WHERE instr(aliasKey, ?) > 0 LIMIT 1", new String[]{FINAL_SIGMA})) {
            if (!aliases.moveToFirst()) return;
        }
        // Collapse equivalent legacy aliases before changing their primary keys.
        database.execSQL("DELETE FROM TagTranslationAliases WHERE rowid NOT IN ("
            + "SELECT MIN(rowid) FROM TagTranslationAliases GROUP BY idTag, locale, replace(aliasKey, ?, ?))",
            new Object[]{FINAL_SIGMA, SIGMA});
        database.execSQL("UPDATE TagTranslationAliases SET aliasKey = replace(aliasKey, ?, ?)"
            + " WHERE instr(aliasKey, ?) > 0", sigmaReplacement);
    }

    public static int installedRevision(SQLiteDatabase database, String locale) {
        String canonicalLocale = validateLocale(locale);
        try (Cursor cursor = database.rawQuery("SELECT revision FROM TagTranslationState WHERE locale = ?",
            new String[]{canonicalLocale})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public static Map<String, Integer> installedPacks(SQLiteDatabase database) {
        Map<String, Integer> packs = new TreeMap<>();
        try (Cursor cursor = database.rawQuery(
            "SELECT locale, revision FROM TagTranslationState ORDER BY locale COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) packs.put(cursor.getString(0), cursor.getInt(1));
        }
        return packs;
    }

    public static String resolveInstalledLocale(SQLiteDatabase database, Locale appLocale) {
        if (appLocale == null || appLocale.getLanguage().isEmpty()) return null;
        List<String> candidates = new ArrayList<>();
        String language = appLocale.toLanguageTag().split("-", 2)[0];
        String script = appLocale.getScript();
        String region = appLocale.getCountry();
        String exact = language + (script.isEmpty() ? "" : "-" + script)
            + (region.isEmpty() ? "" : "-" + region);
        if (isSupportedLocale(exact)) candidates.add(exact);
        if (script.isEmpty() && "zh".equals(language)) {
            script = "TW".equals(region) || "HK".equals(region) || "MO".equals(region) ? "Hant" : "Hans";
        }
        if (!script.isEmpty()) {
            String scriptLocale = language + "-" + script;
            if (!candidates.contains(scriptLocale)) candidates.add(scriptLocale);
        }
        if (!candidates.contains(language)) candidates.add(language);
        for (String candidate : candidates) {
            try (Cursor cursor = database.rawQuery("SELECT 1 FROM TagTranslationState WHERE locale = ?",
                new String[]{candidate})) {
                if (cursor.moveToFirst()) return candidate;
            }
        }
        return null;
    }

    /** The scratch directory must be app-private. The input stream is closed before installation. */
    public static boolean apply(SQLiteDatabase database, InputStream input, File scratchDirectory,
                                BooleanSupplier cancelled) throws IOException {
        return apply(database, input, scratchDirectory, null, 0, cancelled);
    }

    public static boolean apply(SQLiteDatabase database, InputStream input, File scratchDirectory,
                                long generation, BooleanSupplier cancelled) throws IOException {
        if (generation < 0) throw new IllegalArgumentException("Invalid translation generation");
        return apply(database, input, scratchDirectory, null, 0, generation, cancelled);
    }

    static boolean apply(SQLiteDatabase database, InputStream input, File scratchDirectory, String expectedLocale,
                         int expectedRevision, BooleanSupplier cancelled) throws IOException {
        return apply(database, input, () -> openScratch(scratchDirectory), expectedLocale, expectedRevision, -1, cancelled);
    }

    static boolean apply(SQLiteDatabase database, InputStream input, File scratchDirectory, String expectedLocale,
                         int expectedRevision, long generation, BooleanSupplier cancelled) throws IOException {
        return apply(database, input, () -> openScratch(scratchDirectory), expectedLocale, expectedRevision, generation, cancelled);
    }

    static SeekableByteChannel openScratch(File directory) throws IOException {
        Files.createDirectories(directory.toPath());
        return Files.newByteChannel(directory.toPath().resolve("tag-translation-" + UUID.randomUUID() + ".tmp"),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE,
            StandardOpenOption.DELETE_ON_CLOSE);
    }

    @FunctionalInterface
    interface ScratchOpener {
        SeekableByteChannel open() throws IOException;
    }

    static boolean apply(SQLiteDatabase database, InputStream input, ScratchOpener opener, String expectedLocale,
                         int expectedRevision, BooleanSupplier cancelled) throws IOException {
        return apply(database, input, opener, expectedLocale, expectedRevision, -1, cancelled);
    }

    private static boolean apply(SQLiteDatabase database, InputStream input, ScratchOpener opener, String expectedLocale,
                                 int expectedRevision, long generation, BooleanSupplier cancelled) throws IOException {
        SeekableByteChannel scratch = null;
        try {
            PreparedRows prepared;
            PackMetadata metadata;
            // A supplementary code point can be written as two escaped UTF-16 code units.
            try (JsonReader reader = JsonStringGuard.open(new BoundedInputStream(input, cancelled), MAX_TEXT_CODE_POINTS * 12 + 1)) {
                checkCancelled(cancelled);
                if (generation < 0) generation = generationSnapshot(database);
                scratch = opener.open();
                // The channel owns the scratch file; flush its writer before rewinding for SQLite.
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(scratch)));
                prepared = new PreparedRows(output);
                metadata = readPack(reader, prepared, cancelled);
                output.flush();
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw new IOException("Invalid translation pack", error);
            }
            if (expectedLocale != null && (!expectedLocale.equals(metadata.locale) || expectedRevision != metadata.revision)) {
                throw new IOException("Translation pack locale or revision does not match manifest");
            }
            prepared.validateIds();
            scratch.position(0);
            DataInputStream rows = new DataInputStream(new BufferedInputStream(Channels.newInputStream(scratch)));
            checkCancelled(cancelled);
            return install(database, metadata, rows, prepared.entries, prepared.aliases, generation, cancelled);
        } finally {
            if (scratch != null) {
                try {
                    scratch.close();
                } catch (IOException error) {
                    LogUtility.e("Unable to close prepared translation pack", error);
                }
            }
        }
    }

    private static boolean install(SQLiteDatabase database, PackMetadata metadata, DataInputStream rows,
                                   int entryCount, int aliasCount, long generation, BooleanSupplier cancelled) throws IOException {
        database.beginTransactionNonExclusive();
        try {
            checkCancelled(cancelled);
            if (metadata.revision <= installedRevision(database, metadata.locale)) return false;
            if (localeGeneration(database, metadata.locale) > generation) throw new ChangedDuringImportException();
            database.execSQL("DELETE FROM TagTranslationAliases WHERE locale = ?", new Object[]{metadata.locale});
            database.execSQL("DELETE FROM TagTranslations WHERE locale = ?", new Object[]{metadata.locale});
            try (TranslationInsert entries = new TranslationInsert(database, metadata.locale, entryCount,
                    "TagTranslations (idTag, locale, displayName, displayKey)", false);
                 TranslationInsert aliases = new TranslationInsert(database, metadata.locale, aliasCount,
                    "TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)", true)) {
                for (int row = 0; row < entryCount; row++) {
                    checkCancelled(cancelled);
                    int id = rows.readInt();
                    entries.add(id, rows, 0);
                    int aliasesForTag = rows.readUnsignedByte();
                    for (int index = 0; index < aliasesForTag; index++) aliases.add(id, rows, index);
                }
            }
            try (Cursor unknown = database.rawQuery("SELECT 1 FROM TagTranslations e"
                + " LEFT JOIN Tags t ON t.idTag = e.idTag WHERE e.locale = ? AND t.idTag IS NULL LIMIT 1",
                new String[]{metadata.locale})) {
                if (unknown.moveToFirst()) throw new UnknownTagException();
            }
            checkCancelled(cancelled);
            database.execSQL("INSERT OR REPLACE INTO TagTranslationState (locale, revision) VALUES (?, ?)",
                new Object[]{metadata.locale, metadata.revision});
            advanceGeneration(database, metadata.locale);
            checkCancelled(cancelled);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        CHANGES.postValue(null);
        return true;
    }

    public static void remove(SQLiteDatabase database, String locale) {
        removeInternal(database, locale, null, null);
    }

    public static boolean removeIfUnchanged(SQLiteDatabase database, String locale, PackState expected) {
        if (expected == null) throw new IllegalArgumentException("Missing translation state");
        if (!expected.locale.equals(validateLocale(locale))) throw new IllegalArgumentException("Translation locale mismatch");
        return removeInternal(database, locale, expected.revision, expected.generation);
    }

    private static boolean removeInternal(SQLiteDatabase database, String locale, Integer expectedRevision, Long expectedGeneration) {
        String canonicalLocale = validateLocale(locale);
        database.beginTransactionNonExclusive();
        try {
            if (expectedRevision != null && installedRevision(database, canonicalLocale) != expectedRevision) return false;
            if (expectedGeneration != null && localeGeneration(database, canonicalLocale) != expectedGeneration) return false;
            database.delete("TagTranslationAliases", "locale = ?", new String[]{canonicalLocale});
            database.delete("TagTranslations", "locale = ?", new String[]{canonicalLocale});
            database.delete("TagTranslationState", "locale = ?", new String[]{canonicalLocale});
            advanceGeneration(database, canonicalLocale);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        CHANGES.postValue(null);
        return true;
    }

    private static PackMetadata readPack(JsonReader reader, PreparedRows rows, BooleanSupplier cancelled) throws IOException {
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
                    readEntries(reader, rows, cancelled);
                    break;
                default: throw new IOException("Unsupported translation pack field");
            }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT || schemaVersion != 1 || revision == 0 || locale == null
            || rows.entries == 0) throw new IOException("Invalid translation pack header");
        return new PackMetadata(locale, revision);
    }

    private static void readEntries(JsonReader reader, PreparedRows rows, BooleanSupplier cancelled) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            checkCancelled(cancelled);
            if (rows.entries == MAX_ENTRIES) throw new IOException("Translation pack has too many entries");
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
            rows.add(id, name, aliases);
        }
        reader.endArray();
    }

    private static final class PreparedRows {
        private final DataOutputStream output;
        private int[] ids = new int[INSERT_BATCH_SIZE];
        private int entries;
        private int aliases;

        PreparedRows(DataOutputStream output) {
            this.output = output;
        }

        void add(int id, String name, List<String> aliases) throws IOException {
            if (entries == ids.length) ids = Arrays.copyOf(ids, Math.min(MAX_ENTRIES, ids.length * 2));
            ids[entries++] = id;
            this.aliases += aliases.size();
            output.writeInt(id);
            writeText(name, searchKey(name));
            output.writeByte(aliases.size());
            if (!aliases.isEmpty()) {
                Set<String> seen = new HashSet<>();
                for (String alias : aliases) {
                    String key = searchKey(alias);
                    if (!seen.add(key)) throw new IOException("Duplicate normalized translation alias");
                    writeText(alias, key);
                }
            }
            if (output.size() > MAX_PREPARED_BYTES) throw new IOException("Prepared translation pack exceeds size limit");
        }

        private void writeText(String text, String key) throws IOException {
            output.writeUTF(text);
            output.writeUTF(text.equals(key) ? "" : key);
        }

        void validateIds() throws IOException {
            Arrays.sort(ids, 0, entries);
            for (int index = 1; index < entries; index++) {
                if (ids[index] == ids[index - 1]) throw new IOException("Duplicate translation tag ID");
            }
        }
    }

    private static final class TranslationInsert implements AutoCloseable {
        private final SQLiteDatabase database;
        private final String locale;
        private final String table;
        private final boolean ordered;
        private SQLiteStatement statement;
        private int remaining;
        private int size;
        private int bound;

        TranslationInsert(SQLiteDatabase database, String locale, int count, String table, boolean ordered) {
            this.database = database;
            this.locale = locale;
            this.table = table;
            this.ordered = ordered;
            remaining = count;
        }

        void add(int id, DataInputStream rows, int order) throws IOException {
            int nextSize = Math.min(remaining, INSERT_BATCH_SIZE);
            if (nextSize != size) {
                close();
                size = nextSize;
                statement = database.compileStatement("INSERT INTO " + table + " VALUES "
                    + String.join(",", Collections.nCopies(size, ordered ? "(?,?,?,?,?)" : "(?,?,?,?)")));
            }
            String text = rows.readUTF();
            String key = rows.readUTF();
            int parameter = bound * (ordered ? 5 : 4);
            statement.bindLong(parameter + 1, id);
            statement.bindString(parameter + 2, locale);
            statement.bindString(parameter + 3, text);
            statement.bindString(parameter + 4, key.isEmpty() ? text : key);
            if (ordered) statement.bindLong(parameter + 5, order);
            if (++bound == size) {
                statement.execute();
                remaining -= bound;
                bound = 0;
            }
        }

        @Override
        public void close() {
            if (statement != null) {
                statement.close();
                statement = null;
            }
        }
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
            int value = Integer.parseInt(reader.nextString());
            if (value < minimum || value > maximum) throw new IOException("Translation pack integer is out of range");
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid translation pack integer", error);
        }
    }

    private static String validateLocale(String localeTag) {
        if (!isSupportedLocale(localeTag)) throw new IllegalArgumentException("Invalid translation locale");
        return localeTag;
    }

    static boolean isSupportedLocale(String localeTag) {
        if (localeTag == null || !localeTag.matches("[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?")) {
            return false;
        }
        String language = localeTag.split("-", 2)[0];
        return !"in".equals(language) && !"iw".equals(language) && !"ji".equals(language) && !"und".equals(language);
    }

    private static String validateText(String value) throws IOException {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.SURROGATE) {
                throw new IOException("Translation text contains invalid characters");
            }
            offset += Character.charCount(codePoint);
        }
        normalized = normalized.trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints == 0 || codePoints > MAX_TEXT_CODE_POINTS) throw new IOException("Invalid translation text length");
        return normalized;
    }

    static String searchKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replace(FINAL_SIGMA, SIGMA);
    }

    static String prefixUpperBound(String prefix) {
        // The UTF-8 database's BINARY indexes order valid text by Unicode code point.
        int end = prefix.length();
        while (end > 0) {
            int codePoint = prefix.codePointBefore(end);
            int start = end - Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) return null;
            if (codePoint < Character.MAX_CODE_POINT) {
                int nextCodePoint = codePoint + 1;
                if (nextCodePoint == Character.MIN_SURROGATE) nextCodePoint = Character.MAX_SURROGATE + 1;
                return prefix.substring(0, start) + new String(Character.toChars(nextCodePoint));
            }
            end = start;
        }
        return null;
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
