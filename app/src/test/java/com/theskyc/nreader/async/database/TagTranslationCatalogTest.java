package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationCatalogTest {
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        TagTranslationCatalog.createTables(database);
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical-one', 3, 100),"
            + " (2, 'canonical-two', 3, 200)");
    }

    @After public void closeDatabase() {
        database.close();
    }

    @Test public void newerLocaleSnapshotReplacesOnlyItsOwnRowsAndAliases() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\u4e00\",\"aliases\":[\"\u58f9\"]},"
            + "{\"id\":2,\"name\":\"\u4e8c\",\"aliases\":[]}]")));
        assertTrue(apply(pack("ja", 1, "[{\"id\":1,\"name\":\"\u4e00\",\"aliases\":[\"\u3044\u3061\"]}]")));
        assertTrue(apply(pack("zh-Hans", 2, "[{\"id\":2,\"name\":\"\u7b2c\u4e8c\",\"aliases\":[\"\u8d30\"]}]")));

        assertEquals(2, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        assertEquals("\u7b2c\u4e8c", scalarString("SELECT displayName FROM TagTranslations WHERE idTag=2 AND locale='zh-Hans'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM TagTranslations WHERE idTag=1 AND locale='zh-Hans'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM TagTranslationAliases WHERE idTag=2"
            + " AND locale='zh-Hans' AND aliasKey='\u8d30'"));
        assertEquals("\u4e00", scalarString("SELECT displayName FROM TagTranslations WHERE idTag=1 AND locale='ja'"));
        assertEquals("canonical-one", scalarString("SELECT name FROM Tags WHERE idTag=1"));
    }

    @Test public void malformedAndUnknownTagPacksLeavePriorLocaleInstalled() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\u539f\u540d\",\"aliases\":[]}]")));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 2,
            "[{\"id\":1,\"name\":\"\u91cd\u590d\u4e00\",\"aliases\":[]},"
                + "{\"id\":1,\"name\":\"\u91cd\u590d\u4e8c\",\"aliases\":[]}]")));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 3, "[{\"id\":99,\"name\":\"\u672a\u77e5\"}]")));
        assertFalse(apply(pack("zh-Hans", 1, "[{\"id\":2,\"name\":\"\u65e7\u7248\u672c\"}]")));

        assertEquals(1, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        assertEquals("\u539f\u540d", scalarString("SELECT displayName FROM TagTranslations WHERE idTag=1 AND locale='zh-Hans'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM TagTranslations WHERE idTag=2 AND locale='zh-Hans'"));
    }

    @Test public void cancelledInstallationRollsBackAndLocaleRemovalIsScoped() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\u4e00\"}]")));
        assertTrue(apply(pack("ja", 1, "[{\"id\":2,\"name\":\"\u4e8c\"}]")));
        assertThrows(IOException.class, () -> TagTranslationCatalog.apply(database,
            bytes(pack("zh-Hans", 2, "[{\"id\":2,\"name\":\"\u66f4\u65b0\"}]")), () -> true));

        assertEquals(1, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        TagTranslationCatalog.remove(database, "zh-Hans");
        assertEquals(0, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM TagTranslations WHERE locale='ja'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM TagTranslationAliases WHERE locale='zh-Hans'"));
    }

    @Test public void rejectsNonCanonicalLocalesAndInvalidText() {
        assertThrows(IllegalArgumentException.class, () -> TagTranslationCatalog.installedRevision(database, "zh-cn"));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"bad\\nname\"}]")));
        assertThrows(IOException.class, () -> apply("{\"schemaVersion\":1,\"locale\":\"und\","
            + "\"revision\":1,\"entries\":[]}"));
    }

    private boolean apply(String json) throws IOException {
        return TagTranslationCatalog.apply(database, bytes(json), () -> false);
    }

    private ByteArrayInputStream bytes(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private String pack(String locale, int revision, String entries) {
        return "{\"schemaVersion\":1,\"locale\":\"" + locale + "\",\"revision\":" + revision
            + ",\"entries\":" + entries + "}";
    }

    private int scalar(String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private String scalarString(String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }
}
