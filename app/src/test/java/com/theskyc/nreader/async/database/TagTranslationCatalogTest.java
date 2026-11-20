package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

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
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
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
        assertThrows(IOException.class, () -> apply("{\"schemaVersion\":1,\"locale\":\"zh-Hans\","
            + "\"revision\":1,\"entries\":[]}"));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"bad\\nname\"}]")));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\\tname\"}]")));
        assertThrows(IOException.class, () -> apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\\ud800\"}]")));
        assertThrows(IOException.class, () -> apply(pack("iw-IL", 1, "[{\"id\":1,\"name\":\"name\"}]")));
        assertThrows(IOException.class, () -> apply("{\"schemaVersion\":1,\"locale\":\"und\","
            + "\"revision\":1,\"entries\":[]}"));
    }

    @Test public void acceptsMaximumLengthEscapedSupplementaryText() throws IOException {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < 256; i++) escaped.append("\\ud83d\\ude00");
        assertTrue(apply(pack("en", 1, "[{\"id\":1,\"name\":\"" + escaped + "\"}]")));
    }

    @Test public void exactChineseLocaleWinsThenExplicitScriptThenRegion() throws IOException {
        String entries = "[{\"id\":1,\"name\":\"Name\"}]";
        assertTrue(apply(pack("zh", 1, entries)));
        assertTrue(apply(pack("zh-Hans", 1, entries)));
        assertTrue(apply(pack("zh-Hant", 1, entries)));
        assertTrue(apply(pack("zh-TW", 1, entries)));

        assertEquals("zh", TagTranslationCatalog.resolveInstalledLocale(database, Locale.CHINESE));
        assertEquals("zh-TW", TagTranslationCatalog.resolveInstalledLocale(database, Locale.TAIWAN));
        assertEquals("zh-Hans", TagTranslationCatalog.resolveInstalledLocale(database, Locale.forLanguageTag("zh-Hans-TW")));
        assertEquals("zh-Hant", TagTranslationCatalog.resolveInstalledLocale(database, Locale.forLanguageTag("zh-HK")));
    }

    @Test public void translatedAndAliasSuggestionsRetainCanonicalTagIdentity() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\u4e3b\u9898\","
            + "\"aliases\":[\"\u9898\u6750\"]}]")));
        assertTrue(apply(pack("ja", 1, "[{\"id\":2,\"name\":\"\u7d75\u753b\"}]")));
        assertEquals("zh-Hans", TagTranslationCatalog.resolveInstalledLocale(database, Locale.forLanguageTag("zh-CN")));
        assertEquals("ja", TagTranslationCatalog.resolveInstalledLocale(database, Locale.forLanguageTag("ja-JP")));
        assertEquals(null, TagTranslationCatalog.resolveInstalledLocale(database, Locale.forLanguageTag("fr-FR")));

        List<TagSuggestion> translated = Queries.TagTable.getTagSuggestions(TagType.TAG, "\u4e3b", "zh-Hans");
        assertEquals(1, translated.size());
        assertEquals("canonical-one", translated.get(0).getTag().getName());
        assertEquals("\u4e3b\u9898", translated.get(0).getDisplayName());
        assertEquals("\u4e3b\u9898 (canonical-one)", translated.get(0).toString());

        List<TagSuggestion> alias = Queries.TagTable.getTagSuggestions(TagType.TAG, "\u9898", "zh-Hans");
        assertEquals(1, alias.size());
        assertEquals("canonical-one", alias.get(0).getTag().getName());
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
