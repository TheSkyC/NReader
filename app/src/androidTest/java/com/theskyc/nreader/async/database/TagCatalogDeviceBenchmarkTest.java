package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TagCatalogDeviceBenchmarkTest {
    private static final String TAG = "TagCatalogBenchmark";
    private static final String LOCALE = "zh-Hans";
    private static final int SEARCH_RUNS = 25;

    @Test
    public void importsFullCatalogAndSyntheticTranslationThenSearchesIndexedAlias() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SQLiteDatabase database = SQLiteDatabase.create(null);
        File pack = File.createTempFile("tag-device-benchmark-", ".json", context.getCacheDir());
        try {
            Queries.TagTable.createTables(database);
            database.execSQL(TagCatalog.CREATE_STATE);
            TagTranslationCatalog.createTables(database);
            Queries.setDb(database);
            TagV2.initMinCount(context);

            int revision;
            try (java.io.InputStream input = context.getAssets().open("tag-catalog/tagsVersion")) {
                revision = TagCatalog.readRevision(input);
            }
            long catalogStart = SystemClock.elapsedRealtime();
            try (java.io.InputStream input = context.getAssets().open("tag-catalog/tags.json")) {
                assertTrue(TagCatalog.applyBundled(database, input, revision,
                    TagCatalog.MIN_DIRECTORY_ROWS, () -> false));
            }
            long catalogImportMs = SystemClock.elapsedRealtime() - catalogStart;
            long catalogRows = DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null);
            assertTrue(catalogRows >= TagCatalog.MIN_DIRECTORY_ROWS);

            int benchmarkTagId = eligibleTagId(database, TagV2.getMinCount());
            long packRows = writeSyntheticPack(database, pack);
            long translationStart = SystemClock.elapsedRealtime();
            try (java.io.InputStream input = new java.io.FileInputStream(pack)) {
                assertTrue(TagTranslationCatalog.apply(database, input, () -> false));
            }
            long translationImportMs = SystemClock.elapsedRealtime() - translationStart;

            String aliasPrefix = "alias-a-" + benchmarkTagId;
            long[] timings = new long[SEARCH_RUNS];
            List<TagSuggestion> results = Collections.emptyList();
            for (int run = 0; run < SEARCH_RUNS; run++) {
                long start = SystemClock.elapsedRealtimeNanos();
                results = Queries.TagTable.getTagSuggestions(TagType.TAG, aliasPrefix, LOCALE);
                timings[run] = SystemClock.elapsedRealtimeNanos() - start;
            }
            assertEquals(1, results.size());
            assertEquals(benchmarkTagId, results.get(0).getTag().getId());
            assertEquals("translated-" + benchmarkTagId, results.get(0).getDisplayName());

            java.util.Arrays.sort(timings);
            long pageSize = DatabaseUtils.longForQuery(database, "PRAGMA page_size", null);
            long pageCount = DatabaseUtils.longForQuery(database, "PRAGMA page_count", null);
            Log.i(TAG, "rows=" + catalogRows
                + " packEntries=" + packRows
                + " packBytes=" + pack.length()
                + " catalogImportMs=" + catalogImportMs
                + " translationImportMs=" + translationImportMs
                + " aliasSearchMedianMs=" + (timings[SEARCH_RUNS / 2] / 1_000_000.0)
                + " databasePages=" + pageCount
                + " pageSize=" + pageSize);
        } finally {
            Queries.setDb(null);
            database.close();
            if (!pack.delete() && pack.exists()) Log.w(TAG, "Could not remove benchmark pack");
        }
    }

    private static int eligibleTagId(SQLiteDatabase database, int minimumCount) {
        try (Cursor cursor = database.rawQuery("SELECT idTag FROM Tags"
            + " WHERE type = ? AND count >= ? ORDER BY count DESC, idTag LIMIT 1",
            new String[]{Integer.toString(TagType.TAG.getId()), Integer.toString(minimumCount)})) {
            if (!cursor.moveToFirst()) throw new AssertionError("No eligible tag for benchmark query");
            return cursor.getInt(0);
        }
    }

    private static long writeSyntheticPack(SQLiteDatabase database, File file) throws IOException {
        long rows = 0;
        try (Cursor tags = database.rawQuery("SELECT idTag FROM Tags ORDER BY idTag", null);
             JsonWriter writer = new JsonWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.beginObject();
            writer.name("schemaVersion").value(1);
            writer.name("locale").value(LOCALE);
            writer.name("revision").value(1);
            writer.name("entries").beginArray();
            while (tags.moveToNext()) {
                int id = tags.getInt(0);
                writer.beginObject();
                writer.name("id").value(id);
                writer.name("name").value("translated-" + id);
                writer.name("aliases").beginArray();
                writer.value("alias-a-" + id);
                writer.value("alias-b-" + id);
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
