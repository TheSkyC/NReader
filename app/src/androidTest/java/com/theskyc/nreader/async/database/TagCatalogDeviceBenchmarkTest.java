package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.JsonWriter;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.TagV2;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TagCatalogDeviceBenchmarkTest {
    private static final String TAG = "TagCatalogBenchmark";
    private static final String LOCALE = "zh-Hans";
    private static final int SEARCH_RUNS = 25;

    @Test
    public void upgradesLargeVersion26AliasTable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("This fixture requires the isolated benchmark package",
            context.getPackageName().endsWith(".tagbenchmark"));
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        final int count = 172_060;
        try {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                database.execSQL("DROP TABLE TagTranslationAliases");
                database.execSQL("CREATE TABLE TagTranslationAliases (idTag INTEGER NOT NULL, locale TEXT NOT NULL,"
                    + " alias TEXT NOT NULL, aliasKey TEXT NOT NULL, aliasOrder INTEGER NOT NULL DEFAULT -1"
                    + " CHECK (aliasOrder >= -1), PRIMARY KEY (idTag, locale, aliasKey))");
                database.execSQL("CREATE INDEX TagTranslationAliases_locale_key_idx"
                    + " ON TagTranslationAliases (locale, aliasKey, idTag)");
                database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 4)");
                database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (123, 'rule', 1, 2)");
                database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (456)");
                database.beginTransaction();
                try (SQLiteStatement insert = database.compileStatement("INSERT INTO TagTranslationAliases"
                    + " (idTag, locale, alias, aliasKey, aliasOrder) VALUES (?, 'ja', ?, ?, ? )")) {
                    for (int i = 0; i < count; i++) {
                        String alias = "alias-" + i;
                        insert.bindLong(1, i / 2 + 1);
                        insert.bindString(2, alias);
                        insert.bindString(3, alias);
                        insert.bindLong(4, i % 2);
                        insert.executeInsert();
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
                database.setVersion(26);
            }
            long start = SystemClock.elapsedRealtime();
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                long upgradeMs = SystemClock.elapsedRealtime() - start;
                assertEquals(27, database.getVersion());
                assertEquals(count, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslationAliases", null));
                assertEquals(4, TagTranslationCatalog.installedRevision(database, "ja"));
                assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagRules"
                    + " WHERE idTag = 123 AND name = 'rule' AND type = 1 AND status = 2", null));
                assertEquals(1, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM AccountTagBlacklist WHERE idTag = 456", null));
                assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslationAliases"
                    + " WHERE idTag = 86030 AND alias = 'alias-172059' AND aliasOrder = 1", null));
                assertEquals(2, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM sqlite_master"
                    + " WHERE type = 'index' AND name IN ('TagTranslationAliases_locale_key_idx',"
                    + " 'TagTranslationAliases_tag_order_idx')", null));
                Log.i(TAG, "measurement=aliasUpgrade aliases=" + count + " upgradeMs=" + upgradeMs);
            }
        } finally {
            Queries.setDb(null);
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        }
    }

    @Test
    public void importsBundledCatalog() throws Exception {
        withFixture("catalog", (context, database, directory) -> {
            importCatalog(context, database, "measurement");
        });
    }

    @Test
    public void importsFullCatalogTranslationPack() throws Exception {
        withFixture("translation", (context, database, directory) -> {
            long catalogRows = importCatalog(context, database, "setup");
            importTranslation(database, directory, catalogRows, "measurement");
        });
    }

    @Test
    public void queriesInstalledAliases() throws Exception {
        withFixture("alias", (context, database, directory) -> {
            long catalogRows = importCatalog(context, database, "setup");
            importTranslation(database, directory, catalogRows, "setup");

            int benchmarkTagId = eligibleTagId(database, TagV2.getMinCount());
            String aliasPrefix = "alias-a-" + benchmarkTagId + "-";
            long start = SystemClock.elapsedRealtimeNanos();
            List<TagSuggestion> first = Queries.TagTable.getTagSuggestions(TagType.TAG, aliasPrefix, LOCALE);
            long firstQueryNanos = SystemClock.elapsedRealtimeNanos() - start;
            assertAliasResult(benchmarkTagId, first);

            long[] timings = new long[SEARCH_RUNS];
            for (int run = 0; run < SEARCH_RUNS; run++) {
                start = SystemClock.elapsedRealtimeNanos();
                List<TagSuggestion> results = Queries.TagTable.getTagSuggestions(TagType.TAG, aliasPrefix, LOCALE);
                timings[run] = SystemClock.elapsedRealtimeNanos() - start;
                assertAliasResult(benchmarkTagId, results);
            }
            Arrays.sort(timings);
            Log.i(TAG, "measurement=alias catalogRows=" + catalogRows
                + " firstQueryMs=" + (firstQueryNanos / 1_000_000.0)
                + " warmQueries=" + SEARCH_RUNS
                + " warmMedianMs=" + (timings[SEARCH_RUNS / 2] / 1_000_000.0));
        });
    }

    static void withFixture(String name, Benchmark benchmark) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cacheDirectory = context.getCacheDir();
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Could not create benchmark cache directory");
        }
        File directory = Files.createTempDirectory(cacheDirectory.toPath(), "tag-benchmark-").toFile();
        File databaseFile = new File(directory, "benchmark.db");
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)) {
            try {
                Queries.TagTable.createTables(database);
                database.execSQL(TagCatalog.CREATE_STATE);
                TagTranslationCatalog.createTables(database);
                Queries.setDb(database);
                TagV2.initMinCount(context);
                benchmark.run(context, database, directory);
                Log.i(TAG, "case=" + name
                    + " journalMode=" + DatabaseUtils.stringForQuery(database, "PRAGMA journal_mode", null)
                    + " synchronous=" + DatabaseUtils.longForQuery(database, "PRAGMA synchronous", null)
                    + " databasePages=" + DatabaseUtils.longForQuery(database, "PRAGMA page_count", null)
                    + " pageSize=" + DatabaseUtils.longForQuery(database, "PRAGMA page_size", null));
            } finally {
                Queries.setDb(null);
            }
        } finally {
            SQLiteDatabase.deleteDatabase(databaseFile);
            if (!directory.delete()) Log.w(TAG, "Could not remove benchmark directory");
        }
    }

    static long importCatalog(Context context, SQLiteDatabase database, String phase) throws IOException {
        int revision;
        try (InputStream input = context.getAssets().open("tag-catalog/tagsVersion")) {
            revision = TagCatalog.readRevision(input);
        }
        long importMs;
        try (InputStream input = context.getAssets().open("tag-catalog/tags.json")) {
            long start = SystemClock.elapsedRealtime();
            assertTrue(TagCatalog.applyBundled(database, input, revision,
                TagCatalog.MIN_DIRECTORY_ROWS, () -> false));
            importMs = SystemClock.elapsedRealtime() - start;
        }
        assertEquals(revision, TagCatalog.installedSeedRevision(database));
        long rows = DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null);
        assertTrue(rows >= TagCatalog.MIN_DIRECTORY_ROWS);
        Log.i(TAG, phase + "=catalog revision=" + revision + " rows=" + rows + " importMs=" + importMs);
        return rows;
    }

    private static void importTranslation(SQLiteDatabase database, File directory, long catalogRows,
                                          String phase) throws IOException {
        importTranslation(database, directory, catalogRows, phase, LOCALE);
    }

    static void importTranslation(SQLiteDatabase database, File directory, long catalogRows,
                                  String phase, String locale) throws IOException {
        File pack = new File(directory, "translations.json");
        try {
            long start = SystemClock.elapsedRealtime();
            long packRows = writeSyntheticPack(database, pack, locale);
            long generationMs = SystemClock.elapsedRealtime() - start;
            long packBytes = pack.length();
            assertEquals(catalogRows, packRows);
            assertTrue("Synthetic pack exceeds importer byte limit", packBytes <= TagTranslationCatalog.MAX_PACK_BYTES);
            long importMs;
            try (InputStream input = new FileInputStream(pack)) {
                start = SystemClock.elapsedRealtime();
                assertTrue(TagTranslationCatalog.apply(database, input, directory, () -> false));
                importMs = SystemClock.elapsedRealtime() - start;
            }
            assertEquals(1, TagTranslationCatalog.installedRevision(database, locale));
            assertEquals(packRows, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslations", null));
            assertEquals(packRows * 2, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslationAliases", null));
            Log.i(TAG, phase + "=translation packEntries=" + packRows + " packBytes=" + packBytes
                + " setupGenerationMs=" + generationMs + " importMs=" + importMs);
        } finally {
            if (pack.exists() && !pack.delete()) Log.w(TAG, "Could not remove benchmark pack");
        }
    }

    private static void assertAliasResult(int tagId, List<TagSuggestion> results) {
        assertEquals(1, results.size());
        assertEquals(tagId, results.get(0).getTag().getId());
        assertEquals("translated-" + tagId, results.get(0).getDisplayName());
    }

    interface Benchmark {
        void run(Context context, SQLiteDatabase database, File directory) throws Exception;
    }

    static int eligibleTagId(SQLiteDatabase database, int minimumCount) {
        try (Cursor cursor = database.rawQuery("SELECT idTag FROM Tags"
            + " WHERE type = ? AND count >= ? ORDER BY count DESC, idTag LIMIT 1",
            new String[]{Integer.toString(TagType.TAG.getId()), Integer.toString(minimumCount)})) {
            if (!cursor.moveToFirst()) throw new AssertionError("No eligible tag for benchmark query");
            return cursor.getInt(0);
        }
    }

    private static long writeSyntheticPack(SQLiteDatabase database, File file, String locale) throws IOException {
        long rows = 0;
        try (Cursor tags = database.rawQuery("SELECT idTag FROM Tags ORDER BY idTag", null);
             JsonWriter writer = new JsonWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.beginObject();
            writer.name("schemaVersion").value(2);
            writer.name("locale").value(locale);
            writer.name("revision").value(1);
            writer.name("entries").beginArray();
            while (tags.moveToNext()) {
                int id = tags.getInt(0);
                writer.beginObject();
                writer.name("id").value(id);
                writer.name("name").value("translated-" + id);
                writer.name("aliases").beginArray();
                writer.value("alias-a-" + id + "-end");
                writer.value("alias-b-" + id + "-end");
                writer.endArray();
                writer.endObject();
                rows++;
            }
            writer.endArray();
            writer.endObject();
        }
        return rows;
    }
}
