package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
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
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagCatalogManifestTest {
    private static final String SNAPSHOT = "[[1,\"name\",100,1],[2,\"character\",50,2],[3,\"tag\",0,3],"
        + "[4,\"artist\",1,4],[5,\"group\",1,5],[6,\"language\",1,6],[7,\"category\",1,7]]";
    private static final String MANIFEST = "{\"schemaVersion\":1,\"source\":\"nreader-catalog\",\"kind\":\"catalog\","
        + "\"revision\":%d,\"sizeBytes\":%d,\"sha256\":\"%s\"}";

    @Rule public TemporaryFolder files = new TemporaryFolder();
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        database.execSQL(TagCatalog.CREATE_STATE);
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void manifestCannotRedirectClientToAnotherOrigin() throws Exception {
        TagCatalogManifest manifest = readManifest(SNAPSHOT, 7);
        HttpUrl url = TagCatalogManifest.manifestUrl("https://catalog.example:8443/feed/manifest.json");
        assertEquals("https://catalog.example:8443/feed/catalog-" + digest(SNAPSHOT) + ".json",
            manifest.payloadUrl(url).toString());
        for (String bad : new String[]{
            "http://catalog.example/manifest.json", "https://me:secret@catalog.example/manifest.json",
            "https://catalog.example/manifest.json?secret=fixture", "https://catalog.example/manifest.json#fragment",
            "https://catalog.example/feed/", "https://catalog.example/manifest.json/assets"
        }) {
            assertThrows(IOException.class, () -> TagCatalogManifest.manifestUrl(bad));
        }
    }

    @Test public void manifestRejectsUnsupportedDuplicateAndMalformedFields() throws Exception {
        String valid = String.format(Locale.ROOT, MANIFEST, 7, bytes(SNAPSHOT).length, digest(SNAPSHOT));
        for (String bad : new String[]{
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            valid.replace("\"revision\":7", "\"revision\":7.0"),
            valid.replace("\"revision\":7", "\"revision\":7,\"revision\":7"),
            valid.replace("\"revision\":7,", ""),
            valid.replace("\"revision\":7", "\"revision\":-1"),
            valid.replace("\"source\":\"nreader-catalog\"", "\"source\":\"legacy-upstream\""),
            valid.replace("\"kind\":\"catalog\"", "\"kind\":\"locale\""),
            valid.replace("\"kind\":\"catalog\"", "\"kind\":\"cata\\log\""),
            valid.replace("\"sizeBytes\":" + bytes(SNAPSHOT).length, "\"sizeBytes\":16777217"),
            valid.replace(digest(SNAPSHOT), digest(SNAPSHOT).toUpperCase(Locale.ROOT)),
            valid.substring(0, valid.length() - 1) + ",\"url\":\"https://bad.example\"}",
            valid + " []", valid + " ".repeat(TagCatalogManifest.MAX_BYTES)
        }) {
            assertThrows(IOException.class, () -> TagCatalogManifest.parse(bytes(bad)));
        }
    }

    @Test public void validPublishedCatalogWinsOverOtherSourcesAndPreservesRules() throws Exception {
        assertTrue(TagCatalog.applyBundled(database, new ByteArrayInputStream(bytes(SNAPSHOT)), 40, 7, () -> false));
        assertTrue(TagCatalog.apply(database, new ByteArrayInputStream(bytes(SNAPSHOT)), 99, 7, () -> false));
        Tag tag = new Tag("name", 100, 1, TagType.PARODY, TagStatus.AVOIDED);
        Queries.TagTable.updateTag(tag);
        Queries.TagTable.updateBlacklistedTag(tag, true);
        String published = SNAPSHOT.replace("\"name\"", "\"published\"");
        File staged = stage(published);
        assertTrue(readManifest(published, 1).apply(database, staged, 7, () -> false));
        assertFalse(readManifest(SNAPSHOT, 1).apply(database, staged, 7, () -> false));
        assertFalse(TagCatalog.applyBundled(database, new ByteArrayInputStream(bytes(SNAPSHOT)), 41, 7, () -> false));
        assertFalse(TagCatalog.apply(database, new ByteArrayInputStream(bytes(SNAPSHOT)), 100, 7, () -> false));
        assertEquals(1, TagCatalog.installedPublishedRevision(database));
        assertEquals(99, TagCatalog.installedRevision(database));
        assertEquals(40, TagCatalog.installedSeedRevision(database));
        assertEquals("published", Queries.TagTable.getTagById(1).getName());
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.getStatus(tag));
        assertTrue(Queries.TagTable.isBlackListed(tag));
    }

    @Test public void corruptionInterruptionAndStaleReleaseKeepLastGoodRevision() throws Exception {
        assertTrue(readManifest(SNAPSHOT, 2).apply(database, stage(SNAPSHOT), 7, () -> false));
        TagCatalogManifest candidate = readManifest(SNAPSHOT, 3);
        for (String invalid : new String[]{SNAPSHOT + " ", SNAPSHOT.substring(1), SNAPSHOT.replace("name", "same")}) {
            assertThrows(IOException.class, () -> candidate.apply(database, stage(invalid), 7, () -> false));
        }
        String invalid = SNAPSHOT.replace("[7,\"category\",1,7]", "[6,\"duplicate\",1,7]");
        assertThrows(IOException.class,
            () -> readManifest(invalid, 3).apply(database, stage(invalid), 7, () -> false));
        assertThrows(InterruptedIOException.class,
            () -> candidate.apply(database, stage(SNAPSHOT), 7, () -> true));
        assertFalse(readManifest(SNAPSHOT, 1).apply(database, stage(SNAPSHOT), 7, () -> false));
        assertEquals(2, TagCatalog.installedPublishedRevision(database));
        assertEquals("name", Queries.TagTable.getTagById(1).getName());
        String corrected = SNAPSHOT.replace("name", "corrected");
        assertTrue(readManifest(corrected, 3).apply(database, stage(corrected), 7, () -> false));
        assertEquals(3, TagCatalog.installedPublishedRevision(database));
        assertEquals("corrected", Queries.TagTable.getTagById(1).getName());
    }

    private File stage(String content) throws IOException {
        File file = files.newFile();
        Files.write(file.toPath(), bytes(content));
        return file;
    }

    private static TagCatalogManifest readManifest(String content, int revision) throws Exception {
        return TagCatalogManifest.parse(bytes(String.format(Locale.ROOT, MANIFEST,
            revision, bytes(content).length, digest(content))));
    }

    private static String digest(String content) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes(content))) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
