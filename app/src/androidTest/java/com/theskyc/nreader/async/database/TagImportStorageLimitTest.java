package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.util.JsonWriter;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class TagImportStorageLimitTest {
    private static final int TAG_COUNT = 1024;
    private static final String PADDING = "0123456789abcdef" + "fedcba9876543210"
        + "abcdef0123456789" + "9876543210fedcba";

    @Test
    public void fullDatabasePreservesCatalogRulesAndCanRetry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "catalog-page-limit.sqlite");
        byte[] update = directory(TAG_COUNT, "after-" + PADDING);
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            Queries.TagTable.createTables(database);
            database.execSQL(TagCatalog.CREATE_STATE);
            assertTrue(TagCatalog.apply(database, stream(directory(7, "before")), 1, 7, () -> false));
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'before', 1, 1)");
            long normalMaximum = restrictGrowth(database);

            assertThrows(SQLiteFullException.class, () -> TagCatalog.apply(database, stream(update), 2, 7, () -> false));
            assertEquals(1, TagCatalog.installedRevision(database));
            assertEquals(7, number(database, "SELECT COUNT(*) FROM Tags"));
            assertEquals("before", text(database, "SELECT name FROM Tags WHERE idTag = 1"));
            assertEquals(1, number(database, "SELECT status FROM TagRules WHERE idTag = 1"));

            assertEquals(normalMaximum, database.setMaximumSize(normalMaximum));
            assertTrue(TagCatalog.apply(database, stream(update), 2, 7, () -> false));
            assertEquals(2, TagCatalog.installedRevision(database));
            assertEquals(TAG_COUNT, number(database, "SELECT COUNT(*) FROM Tags"));
            assertEquals("after-" + PADDING, text(database, "SELECT name FROM Tags WHERE idTag = 1"));
            assertEquals(1, number(database, "SELECT status FROM TagRules WHERE idTag = 1"));
        } finally {
            SQLiteDatabase.deleteDatabase(file);
        }
    }

    @Test
    public void fullDatabasePreservesInstalledLocalesAndCanRetry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cache = context.getCacheDir();
        File file = new File(cache, "translation-page-limit.sqlite");
        byte[] update = translation(TAG_COUNT);
        byte[] otherLocale = ("{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"other\"}]}").getBytes(StandardCharsets.UTF_8);
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            Queries.TagTable.createTables(database);
            database.execSQL(TagCatalog.CREATE_STATE);
            TagTranslationCatalog.createTables(database);
            assertTrue(TagCatalog.apply(database, stream(directory(TAG_COUNT, "canonical")), 1, 7, () -> false));
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'canonical', 1, 1)");
            assertTrue(TagTranslationCatalog.apply(database, stream(TranslationRecoveryFixture.payload(1, "before")),
                cache, () -> false));
            assertTrue(TagTranslationCatalog.apply(database, stream(otherLocale), cache, () -> false));
            long normalMaximum = restrictGrowth(database);

            assertThrows(SQLiteFullException.class, () -> TagTranslationCatalog.apply(database, stream(update), cache, () -> false));
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
            assertEquals("other", text(database, "SELECT displayName FROM TagTranslations WHERE locale = 'en'"));

            assertEquals(normalMaximum, database.setMaximumSize(normalMaximum));
            assertTrue(TagTranslationCatalog.apply(database, stream(update), cache, () -> false));
            assertEquals(2, TagTranslationCatalog.installedRevision(database, "ja"));
            assertEquals(TAG_COUNT, number(database, "SELECT COUNT(*) FROM TagTranslations WHERE locale = 'ja'"));
            assertEquals(TAG_COUNT, number(database, "SELECT COUNT(*) FROM TagTranslationAliases WHERE locale = 'ja'"));
            assertEquals(1, number(database, "SELECT status FROM TagRules WHERE idTag = 1"));
            assertEquals("other", text(database, "SELECT displayName FROM TagTranslations WHERE locale = 'en'"));
        } finally {
            SQLiteDatabase.deleteDatabase(file);
        }
    }

    private static long restrictGrowth(SQLiteDatabase database) {
        long normalMaximum = database.getMaximumSize();
        long currentSize = number(database, "PRAGMA page_count") * database.getPageSize();
        assertTrue(currentSize < normalMaximum);
        assertEquals(currentSize, database.setMaximumSize(currentSize));
        return normalMaximum;
    }

    private static byte[] directory(int rows, String firstName) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.beginArray();
            for (int id = 1; id <= rows; id++) {
                json.beginArray().value(id).value(id == 1 ? firstName : PADDING + id)
                    .value(100).value(id <= 7 ? id : 3).endArray();
            }
            json.endArray();
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] translation(int rows) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.beginObject();
            json.name("schemaVersion").value(2);
            json.name("locale").value("ja");
            json.name("revision").value(2);
            json.name("entries").beginArray();
            for (int id = 1; id <= rows; id++) {
                json.beginObject();
                json.name("id").value(id);
                json.name("name").value("Translated-" + PADDING + id);
                json.name("aliases").beginArray().value("Alias-" + PADDING + id).endArray();
                json.endObject();
            }
            json.endArray().endObject();
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long number(SQLiteDatabase database, String sql) {
        return DatabaseUtils.longForQuery(database, sql, null);
    }

    private static String text(SQLiteDatabase database, String sql) {
        return DatabaseUtils.stringForQuery(database, sql, null);
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
