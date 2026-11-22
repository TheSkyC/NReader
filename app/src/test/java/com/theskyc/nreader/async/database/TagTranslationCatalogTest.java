package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationCatalogTest {
    @Rule public TemporaryFolder scratch = new TemporaryFolder();
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
        assertEquals(0, scratch.getRoot().list().length);
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

    @Test public void rejectsNonIntegerPackFieldsWithoutChangingInstalledPack() throws IOException {
        assertTrue(apply(pack("ja", 1, "[{\"id\":1,\"name\":\"before\"}]")));
        String update = pack("ja", 2, "[{\"id\":1,\"name\":\"after\"}]");
        String[] invalidPacks = {
            update.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            update.replace("\"revision\":2", "\"revision\":2e0"),
            update.replace("\"id\":1", "\"id\":1.00000000000000001")
        };
        for (String invalid : invalidPacks) {
            assertThrows(invalid, IOException.class, () -> apply(invalid));
        }
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEquals("before", scalarString("SELECT displayName FROM TagTranslations WHERE locale = 'ja'"));
        assertTrue(apply(update));
        assertEquals(2, TagTranslationCatalog.installedRevision(database, "ja"));
    }

    @Test public void cancelledInstallationRollsBackAndLocaleRemovalIsScoped() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"\u4e00\"}]")));
        assertTrue(apply(pack("ja", 1, "[{\"id\":2,\"name\":\"\u4e8c\"}]")));
        assertThrows(IOException.class, () -> TagTranslationCatalog.apply(database,
            bytes(pack("zh-Hans", 2, "[{\"id\":2,\"name\":\"\u66f4\u65b0\"}]")), scratch.getRoot(),
            () -> database.inTransaction() && TagTranslationCatalog.installedRevision(database, "zh-Hans") == 2));

        assertEquals(1, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        assertEquals("\u4e00", scalarString("SELECT displayName FROM TagTranslations WHERE locale='zh-Hans'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM TagTranslations WHERE idTag=2 AND locale='zh-Hans'"));
        TagTranslationCatalog.remove(database, "zh-Hans");
        assertEquals(0, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM TagTranslations WHERE locale='ja'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM TagTranslationAliases WHERE locale='zh-Hans'"));
    }

    @Test public void olderPreparationCannotReplaceARevisionInstalledWhileReading() throws IOException {
        assertTrue(apply(pack("ja", 1, "[{\"id\":1,\"name\":\"first\"}]")));
        try (FilterInputStream slower = new FilterInputStream(bytes(pack("ja", 2, "[{\"id\":1,\"name\":\"older\"}]"))) {
            private boolean committedNewer;

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                assertFalse(database.inTransaction());
                if (!committedNewer) {
                    assertTrue(apply(pack("ja", 3, "[{\"id\":1,\"name\":\"newer\"}]")));
                    committedNewer = true;
                }
                return super.read(buffer, offset, length);
            }
        }) {
            assertFalse(TagTranslationCatalog.apply(database, slower, scratch.getRoot(), () -> false));
        }
        assertEquals(3, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEquals("newer", scalarString("SELECT displayName FROM TagTranslations WHERE locale='ja'"));
    }

    @Test public void batchBoundariesKeepDistinctNamesAndAliasKeys() throws IOException {
        StringJoiner entries = new StringJoiner(",", "[", "]");
        for (int id = 1; id <= 131; id++) {
            if (id > 2) database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (?, 'tag', 3, 100)",
                new Object[]{id});
            entries.add("{\"id\":" + id + ",\"name\":\"Caf\u00e9\uD83D\uDE00" + id
                + "\",\"aliases\":[\"Alias" + id + "\",\"second" + id + "\"]}");
        }
        assertTrue(apply(pack("ja", 1, entries.toString())));
        assertEquals(131, scalar("SELECT COUNT(*) FROM TagTranslations WHERE locale = 'ja'"));
        assertEquals(262, scalar("SELECT COUNT(*) FROM TagTranslationAliases WHERE locale = 'ja'"));
        assertEquals("Caf\u00e9\uD83D\uDE00131",
            scalarString("SELECT displayName FROM TagTranslations WHERE idTag = 131 AND locale = 'ja'"));
        assertEquals("Alias131", scalarString(
            "SELECT alias FROM TagTranslationAliases WHERE idTag = 131 AND locale = 'ja' AND aliasKey = 'alias131'"));
    }

    @Test public void providerCloseFailureCannotCommitPreparedRows() throws IOException {
        assertTrue(apply(pack("ja", 1, "[{\"id\":1,\"name\":\"before\"}]")));
        FilterInputStream update = new FilterInputStream(bytes(pack("ja", 2, "[{\"id\":1,\"name\":\"after\"}]"))) {
            @Override public void close() throws IOException {
                super.close();
                throw new IOException("Synthetic provider close failure");
            }
        };
        assertThrows(IOException.class, () -> TagTranslationCatalog.apply(database, update, scratch.getRoot(), () -> false));
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEquals("before", scalarString("SELECT displayName FROM TagTranslations WHERE locale='ja'"));
    }

    @Test public void installedPacksExposeOnlyCommittedLocalesAndRevisions() throws IOException {
        assertTrue(apply(pack("ja", 2, "[{\"id\":1,\"name\":\"name\"}]")));
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":2,\"name\":\"name\"}]")));

        Map<String, Integer> packs = TagTranslationCatalog.installedPacks(database);
        assertEquals(2, packs.size());
        assertEquals(Integer.valueOf(2), packs.get("ja"));
        assertEquals(Integer.valueOf(1), packs.get("zh-Hans"));

        TagTranslationCatalog.remove(database, "ja");
        assertEquals(1, TagTranslationCatalog.installedPacks(database).size());
        assertTrue(TagTranslationCatalog.installedPacks(database).containsKey("zh-Hans"));
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

    @Test public void greekSigmaPrefixKeysAreStableAcrossWordBoundaries() throws IOException {
        assertTrue(apply(pack("el", 1, "[{\"id\":1,\"name\":\"\u039f\u03a3\u0391\","
            + "\"aliases\":[\"\u039f\u03a3\"]}]")));

        assertEquals("\u03bf\u03c3\u03b1", scalarString(
            "SELECT displayKey FROM TagTranslations WHERE idTag=1 AND locale='el'"));
        assertEquals("\u03bf\u03c3", scalarString(
            "SELECT aliasKey FROM TagTranslationAliases WHERE idTag=1 AND locale='el'"));
        assertEquals(1, Queries.TagTable.getTagSuggestions(TagType.TAG, "\u039f\u03a3", "el").size());
    }

    @Test public void displayLookupOnlyReturnsRequestedPositiveIdsInTheResolvedLocale() throws IOException {
        assertTrue(apply(pack("zh-Hans", 1, "[{\"id\":1,\"name\":\"simplified one\"},"
            + "{\"id\":2,\"name\":\"simplified two\"}]")));
        StringJoiner entries = new StringJoiner(",", "[", "]");
        entries.add("{\"id\":2,\"name\":\"unrequested\"}");
        List<Integer> ids = new ArrayList<>(List.of(-1, 0, 1, 1));
        Map<Integer, String> expected = new HashMap<>();
        for (int id = 1; id <= 300; id++) {
            if (id == 2) continue;
            String translated = "traditional " + id;
            if (id != 1) {
                database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (?, ?, 3, 100)",
                    new Object[]{id, "canonical " + id});
                ids.add(id);
            }
            expected.put(id, translated);
            entries.add("{\"id\":" + id + ",\"name\":\"" + translated + "\"}");
        }
        ids.add(12345);
        assertTrue(apply(pack("zh-Hant", 1, entries.toString())));

        assertEquals(expected, Queries.TagTable.getDisplayNames(ids, Locale.TAIWAN));
        assertEquals(Map.of(2, "simplified two"), Queries.TagTable.getDisplayNames(List.of(2), Locale.CHINA));
        assertTrue(Queries.TagTable.getDisplayNames(ids, Locale.JAPAN).isEmpty());
        TagTranslationCatalog.remove(database, "zh-Hant");
        assertTrue(Queries.TagTable.getDisplayNames(ids, Locale.TAIWAN).isEmpty());
    }

    private boolean apply(String json) throws IOException {
        return TagTranslationCatalog.apply(database, bytes(json), scratch.getRoot(), () -> false);
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
