package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationManifestTest {
    private static final String PACK = "{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":2,"
        + "\"entries\":[{\"id\":1,\"name\":\"\u753b\u5bb6\",\"aliases\":[\"\u3048\"]}]}\n";
    private static final String DESCRIPTOR = "{\"locale\":\"%s\",\"revision\":%d,\"sizeBytes\":%d,\"sha256\":\"%s\"}";
    private static final String MANIFEST = "{\"schemaVersion\":1,\"source\":\"nreader-translations\","
        + "\"kind\":\"translations\",\"packs\":[%s]}\n";

    @Rule public TemporaryFolder files = new TemporaryFolder();
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        TagTranslationCatalog.createTables(database);
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical', 3, 100)");
    }

    @After public void closeDatabase() {
        database.close();
    }

    @Test public void readsIndependentLocalesAndResolvesOnlyDigestSiblings() throws Exception {
        String rows = descriptor("ja", 2, PACK) + "," + descriptor("zh-Hant", 7, PACK);
        Map<String, TagTranslationManifest.Pack> packs = TagTranslationManifest.parse(bytes(manifest(rows))).getPacks();
        assertEquals(2, packs.size());
        assertEquals(7, packs.get("zh-Hant").revision);
        HttpUrl pointer = TagTranslationManifest.manifestUrl("https://feed.example:8443/tags/manifest.json");
        assertEquals("https://feed.example:8443/tags/translation-" + digest(PACK) + ".json",
            packs.get("ja").payloadUrl(pointer).toString());
        assertThrows(IOException.class, () -> TagTranslationManifest.manifestUrl("http://feed.example/manifest.json"));
        assertThrows(IOException.class, () -> TagTranslationManifest.manifestUrl("https://feed.example/manifest.json?key=x"));
    }

    @Test public void invalidManifestsFailBeforeAnyPackIsExposed() throws Exception {
        String row = descriptor("ja", 2, PACK);
        String valid = manifest(row);
        for (String bad : new String[]{
            valid.replace("nreader-translations", "nreader-catalog"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1e0"),
            valid.replace("\"locale\":\"ja\"", "\"locale\":\"../ja\""),
            valid.replace("\"locale\":\"ja\"", "\"locale\":\"iw\""),
            valid.replace("\"revision\":2", "\"revision\":2,\"revision\":2"),
            valid.replace("\"revision\":2,", ""),
            valid.replace(digest(PACK), "A".repeat(64)),
            valid.replace("\"sizeBytes\":" + bytes(PACK).length, "\"sizeBytes\":16777217"),
            manifest(row.substring(0, row.length() - 1) + ",\"url\":\"https://other.example/x\"}"),
            manifest(row + "," + row), manifest(""), valid + "{}", "\ufeff" + valid,
            " ".repeat(TagTranslationManifest.MAX_BYTES) + valid
        }) {
            assertThrows(IOException.class, () -> TagTranslationManifest.parse(bytes(bad)));
        }
        StringBuilder rows = new StringBuilder();
        for (int region = 100; region <= 228; region++) {
            if (rows.length() != 0) rows.append(',');
            rows.append(descriptor("ja-" + region, 2, PACK));
        }
        assertThrows(IOException.class, () -> TagTranslationManifest.parse(bytes(manifest(rows.toString()))));
    }

    @Test public void verifiedPackCanReplaceOnlyItsOwnLocale() throws Exception {
        String other = PACK.replace("\"ja\"", "\"zh-Hant\"");
        assertTrue(pack("ja", 2, PACK).apply(database, stage(PACK), () -> false));
        assertTrue(pack("zh-Hant", 2, other).apply(database, stage(other), () -> false));
        String updated = PACK.replace("\"revision\":2", "\"revision\":3").replace("\u753b\u5bb6", "updated");
        assertTrue(pack("ja", 3, updated).apply(database, stage(updated), () -> false));
        assertFalse(pack("ja", 2, PACK).apply(database, stage(PACK), () -> false));
        assertEquals(Map.of("ja", 3, "zh-Hant", 2), TagTranslationCatalog.installedPacks(database));
    }

    @Test public void mismatchCorruptionCancellationAndUnknownIdsKeepTheLastGoodPack() throws Exception {
        assertTrue(pack("ja", 2, PACK).apply(database, stage(PACK), () -> false));
        String update = PACK.replace("\"revision\":2", "\"revision\":3");
        TagTranslationManifest.Pack descriptor = pack("ja", 3, update);
        for (String corrupt : new String[]{update + " ", update.substring(1), update.replace("\u753b\u5bb6", "\u5225\u540d")}) {
            assertThrows(IOException.class, () -> descriptor.apply(database, stage(corrupt), () -> false));
        }
        assertThrows(InterruptedIOException.class, () -> descriptor.apply(database, stage(update), () -> true));
        assertThrows(IOException.class, () -> pack("ja", 3, PACK).apply(database, stage(PACK), () -> false));
        assertThrows(IOException.class, () -> pack("zh-Hant", 3, update).apply(database, stage(update), () -> false));
        String unknownId = update.replace("\"id\":1", "\"id\":2");
        assertThrows(IOException.class, () -> pack("ja", 3, unknownId).apply(database, stage(unknownId), () -> false));
        String cancelled = update.replace("\u753b\u5bb6", "precommit");
        assertThrows(InterruptedIOException.class, () -> pack("ja", 3, cancelled).apply(database, stage(cancelled),
            () -> TagTranslationCatalog.installedRevision(database, "ja") == 3));
        assertEquals(Map.of("ja", 2), TagTranslationCatalog.installedPacks(database));
        try (Cursor rows = database.rawQuery("SELECT displayName FROM TagTranslations", null)) {
            assertEquals(1, rows.getCount());
            assertTrue(rows.moveToFirst());
            assertEquals("\u753b\u5bb6", rows.getString(0));
        }
    }

    @Test public void newerTranslationCanBeRetriedAfterCatalogCatchesUp() throws Exception {
        assertTrue(pack("ja", 2, PACK).apply(database, stage(PACK), () -> false));
        String update = "{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":3,\"entries\":["
            + "{\"id\":1,\"name\":\"updated\",\"aliases\":[\"new alias\"]},"
            + "{\"id\":2,\"name\":\"new tag\",\"aliases\":[\"new tag alias\"]}]}";
        TagTranslationManifest.Pack descriptor = pack("ja", 3, update);
        assertThrows(IOException.class, () -> descriptor.apply(database, stage(update), () -> false));
        assertEquals(Map.of("ja", 2), TagTranslationCatalog.installedPacks(database));
        try (Cursor rows = database.rawQuery("SELECT displayName FROM TagTranslations WHERE locale = 'ja'", null);
             Cursor aliases = database.rawQuery("SELECT alias FROM TagTranslationAliases WHERE locale = 'ja'", null)) {
            assertTrue(rows.moveToFirst());
            assertEquals("\u753b\u5bb6", rows.getString(0));
            assertEquals(1, rows.getCount());
            assertTrue(aliases.moveToFirst());
            assertEquals("\u3048", aliases.getString(0));
            assertEquals(1, aliases.getCount());
        }

        database.execSQL(TagCatalog.CREATE_STATE);
        String catalog = "[[1,\"canonical\",100,3],[2,\"new\",10,3],[3,\"parody\",1,1],"
            + "[4,\"character\",1,2],[5,\"artist\",1,4],[6,\"group\",1,5],"
            + "[7,\"language\",1,6],[8,\"category\",1,7]]";
        assertTrue(TagCatalog.applyPublished(database, new ByteArrayInputStream(bytes(catalog)), 1, 1, () -> false));
        assertTrue(descriptor.apply(database, stage(update), () -> false));
        assertEquals(Map.of("ja", 3), TagTranslationCatalog.installedPacks(database));
        try (Cursor rows = database.rawQuery("SELECT idTag, displayName FROM TagTranslations WHERE locale = 'ja' ORDER BY idTag", null);
             Cursor aliases = database.rawQuery("SELECT idTag, alias FROM TagTranslationAliases WHERE locale = 'ja' ORDER BY idTag", null)) {
            assertEquals(2, rows.getCount());
            assertTrue(rows.moveToFirst());
            assertEquals(1, rows.getInt(0));
            assertEquals("updated", rows.getString(1));
            assertTrue(rows.moveToNext());
            assertEquals(2, rows.getInt(0));
            assertEquals("new tag", rows.getString(1));
            assertEquals(2, aliases.getCount());
            assertTrue(aliases.moveToFirst());
            assertEquals("new alias", aliases.getString(1));
            assertTrue(aliases.moveToNext());
            assertEquals("new tag alias", aliases.getString(1));
        }
    }

    private static TagTranslationManifest.Pack pack(String locale, int revision, String payload) throws Exception {
        return TagTranslationManifest.parse(bytes(manifest(descriptor(locale, revision, payload)))).getPacks().get(locale);
    }

    private static String descriptor(String locale, int revision, String payload) throws Exception {
        return String.format(Locale.ROOT, DESCRIPTOR, locale, revision, bytes(payload).length, digest(payload));
    }

    private static String manifest(String rows) {
        return String.format(Locale.ROOT, MANIFEST, rows);
    }

    private File stage(String payload) throws IOException {
        File file = files.newFile();
        Files.write(file.toPath(), bytes(payload));
        return file;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(String value) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte digit : MessageDigest.getInstance("SHA-256").digest(bytes(value))) {
            hex.append(String.format(Locale.ROOT, "%02x", digit));
        }
        return hex.toString();
    }
}
