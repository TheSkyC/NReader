package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.api.enums.TagType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class TagUnicodeMigrationDeviceTest {
    @Test public void rebuildsLargeStoredTranslationIndex() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        int entries = 86030;
        try {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                database.beginTransaction();
                try (SQLiteStatement display = database.compileStatement("INSERT INTO TagTranslations"
                        + " (idTag, locale, displayName, displayKey) VALUES (?, 'ja', ?, 'old')");
                     SQLiteStatement alias = database.compileStatement("INSERT INTO TagTranslationAliases"
                        + " (idTag, locale, alias, aliasKey, aliasOrder) VALUES (?, 'ja', ?, 'old', ?)")) {
                    for (int id = 1; id <= entries; id++) {
                        display.bindLong(1, id);
                        display.bindString(2, "\u1c89 name " + id);
                        display.executeInsert();
                        for (int order = 0; order < 2; order++) {
                            alias.bindLong(1, id);
                            alias.bindString(2, "\u1c89 alias " + id + " " + order);
                            alias.bindLong(3, order);
                            alias.executeInsert();
                        }
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
                database.setVersion(27);
            }
            long start = SystemClock.elapsedRealtime();
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                long openMs = SystemClock.elapsedRealtime() - start;
                assertEquals(29, database.getVersion());
                assertTrue(TagTranslationCatalog.isKeyRebuildPending(database));
                long rebuildStart = SystemClock.elapsedRealtime();
                long maxBatchMs = 0;
                boolean finished;
                do {
                    long batchStart = SystemClock.elapsedRealtime();
                    finished = TagTranslationCatalog.rebuildUnicodeKeyBatch(database, () -> false);
                    maxBatchMs = Math.max(maxBatchMs, SystemClock.elapsedRealtime() - batchStart);
                } while (!finished);
                long rebuildMs = SystemClock.elapsedRealtime() - rebuildStart;
                assertEquals(entries, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM TagTranslations WHERE displayName = '\u1c89 name ' || idTag"
                        + " AND displayKey = '\u1c8a name ' || idTag", null));
                assertEquals(entries * 2L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM TagTranslationAliases WHERE alias = '\u1c89 alias ' || idTag || ' ' || aliasOrder"
                        + " AND aliasKey = '\u1c8a alias ' || idTag || ' ' || aliasOrder", null));
                Log.i("TagUnicodeMigration", "entries=" + entries + " aliases=" + entries * 2
                    + " openMs=" + openMs + " rebuildMs=" + rebuildMs + " maxBatchMs=" + maxBatchMs);
            }
        } finally {
            Queries.setDb(null);
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        }
    }

    @Test public void upgradeAndImportUseSameUnicodeKeys() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700004, 'canonical', 3, 200000)");
                database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (700004, 'rule', 3, 1)");
                database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (700004)");
                database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
                database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                    + " VALUES (700004, 'ja', 'name', 'name')");
                database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                    + " VALUES (700004, 'ja', '\u1c89 old', '\u1c89 old', 0)");
                database.setVersion(27);
            }
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                assertEquals(29, database.getVersion());
                assertTrue(TagTranslationCatalog.isKeyRebuildPending(database));
                while (!TagTranslationCatalog.rebuildUnicodeKeyBatch(database, () -> false)) { }
                assertEquals("\u1c8a old", DatabaseUtils.stringForQuery(database,
                    "SELECT aliasKey FROM TagTranslationAliases", null));
                assertEquals(1, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM TagRules WHERE idTag=700004 AND status=1", null));
                assertEquals(1, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM AccountTagBlacklist WHERE idTag=700004", null));
                assertEquals(700004, Queries.TagTable.getTagSuggestions(TagType.TAG, "\u1c8a", "ja")
                    .get(0).getTag().getId());
                String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":2,"
                    + "\"entries\":[{\"id\":700004,\"name\":\"new\",\"aliases\":[\"\u1c89 new\"]}]}";
                assertTrue(TagTranslationCatalog.apply(database,
                    new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), context.getCacheDir(), () -> false));
                assertEquals(700004, Queries.TagTable.getTagSuggestions(TagType.TAG, "\u1c8a", "ja")
                    .get(0).getTag().getId());
                assertEquals(2, TagTranslationCatalog.installedRevision(database, "ja"));
            }
        } finally {
            Queries.setDb(null);
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        }
    }
}
