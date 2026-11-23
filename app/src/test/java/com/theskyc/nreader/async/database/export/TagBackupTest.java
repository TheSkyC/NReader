package com.theskyc.nreader.async.database.export;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.TagV2;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagBackupTest {
    @Test public void portableBackupRestoresRulesAndReferencesThenUndoRestoresOnlyPreviousUserData() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        File backup = new File(app.getCacheDir(), "portable-tags.zip");
        Tag gallery = new Tag("reference", 2, 301, TagType.TAG, TagStatus.AVOIDED);
        Tag bookmark = new Tag("bookmark", 3, 305, TagType.TAG, TagStatus.DEFAULT);
        Tag catalogOnly = new Tag("public entry", 5000, 302, TagType.TAG, TagStatus.DEFAULT);
        Tag accountOnly = new Tag("online entry", 5, 303, TagType.TAG, TagStatus.DEFAULT);
        try (DatabaseHelper source = new DatabaseHelper(app)) {
            SQLiteDatabase db = source.getWritableDatabase();
            Queries.TagTable.insert(gallery);
            Queries.TagTable.insert(bookmark);
            Queries.TagTable.insert(catalogOnly);
            assertTrue(TagTranslationCatalog.apply(db, new ByteArrayInputStream((
                "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,\"entries\":["
                    + "{\"id\":301,\"name\":\"translated source\",\"aliases\":[\"source alias\"]}]}"
            ).getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            Queries.TagTable.updateTag(gallery);
            Queries.TagTable.updateBlacklistedTag(accountOnly, true);
            db.execSQL("INSERT INTO Gallery VALUES (500, 'fixture', '', '', 0, 1, '', 0, 0, 0, 0, 0)");
            db.execSQL("INSERT INTO Favorite (id_gallery, time) VALUES (500, 1)");
            db.execSQL("INSERT INTO GalleryTags VALUES (500, 301)");
            db.execSQL("INSERT INTO Bookmark VALUES ('fixture', 0, 1, 305)");
            db.execSQL("UPDATE Tags SET name = 'renamed reference' WHERE idTag = 301");
            Exporter.exportData(app, Uri.fromFile(backup));
        }

        BackupArchive archive = BackupArchive.read(Files.newInputStream(backup.toPath()), null);
        JSONObject contents = new JSONObject(new String(archive.database, StandardCharsets.UTF_8));
        assertEquals(4, archive.formatVersion);
        assertFalse(contents.has("Tags"));
        assertFalse(contents.has("AccountTagBlacklist"));
        assertFalse(contents.has("TagCatalogState"));
        assertFalse(contents.has("TagTranslations"));
        assertFalse(contents.has("TagTranslationAliases"));
        assertFalse(contents.has("TagTranslationState"));
        assertEquals(2, contents.getJSONArray("TagReferences").length());
        assertEquals("renamed reference", contents.getJSONArray("TagRules").getJSONObject(0).getString("name"));

        app.deleteDatabase("Entries.db");
        try (DatabaseHelper target = new DatabaseHelper(app)) {
            SQLiteDatabase db = target.getWritableDatabase();
            Tag previous = new Tag("prior", 2, 304, TagType.TAG, TagStatus.ACCEPTED);
            Queries.TagTable.updateTag(previous);
            Queries.TagTable.insert(catalogOnly);
            db.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('legacy-upstream', 44)");
            assertTrue(TagTranslationCatalog.apply(db, new ByteArrayInputStream((
                "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":7,\"entries\":["
                    + "{\"id\":302,\"name\":\"target translation\",\"aliases\":[\"target alias\"]}]}"
            ).getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            Importer.importData(app, Uri.fromFile(backup));
            assertEquals(44, TagCatalog.installedRevision(db));
            assertEquals(7, TagTranslationCatalog.installedRevision(db, "ja"));
            try (Cursor translation = db.rawQuery(
                "SELECT displayName FROM TagTranslations WHERE idTag = 302 AND locale = 'ja'", null)) {
                assertTrue(translation.moveToFirst());
                assertEquals("target translation", translation.getString(0));
            }
            try (Cursor alias = db.rawQuery(
                "SELECT alias FROM TagTranslationAliases WHERE idTag = 302 AND locale = 'ja'", null)) {
                assertTrue(alias.moveToFirst());
                assertEquals("target alias", alias.getString(0));
            }
            assertNull(Queries.TagTable.getTagById(accountOnly.getId()));
            assertNotNull(Queries.TagTable.getTagById(catalogOnly.getId()));
            assertEquals("renamed reference", Queries.TagTable.getTagById(gallery.getId()).getName());
            assertEquals("bookmark", Queries.TagTable.getTagById(bookmark.getId()).getName());
            assertEquals(1, Queries.GalleryBridgeTable.getTagsForGallery(500).getCount(TagType.TAG));
            assertEquals(TagStatus.AVOIDED, Queries.TagTable.getStatus(gallery));
            assertNull(Queries.TagTable.getStatus(previous));

            Importer.restoreLastSnapshot(app);
            assertEquals(44, TagCatalog.installedRevision(db));
            assertEquals(7, TagTranslationCatalog.installedRevision(db, "ja"));
            try (Cursor translation = db.rawQuery(
                "SELECT displayName FROM TagTranslations WHERE idTag = 302 AND locale = 'ja'", null)) {
                assertTrue(translation.moveToFirst());
                assertEquals("target translation", translation.getString(0));
            }
            assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getStatus(previous));
            assertEquals(TagStatus.DEFAULT, Queries.TagTable.getStatus(gallery));
            assertEquals(0, Queries.GalleryBridgeTable.getTagsForGallery(500).getCount(TagType.TAG));
        }
    }

    @Test public void malformedVersionFourRestoreKeepsExistingRulesAndCatalog() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            Tag rule = new Tag("found%_literally", 0, 312, TagType.TAG, TagStatus.AVOIDED);
            Queries.TagTable.updateTag(rule);
            int priorThreshold = TagV2.getMinCount();
            TagV2.updateMinCount(app, 1000);
            try {
                byte[] data = ("{\"TagRules\":[],\"TagReferences\":[{\"idTag\":313,\"name\":\"invalid\","
                    + "\"type\":3,\"count\":0,\"online\":1}]}").getBytes(StandardCharsets.UTF_8);
                File malformed = archive(app, data, 4);
                db.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('legacy-upstream', 50)");
                assertThrows(IOException.class, () -> Importer.importData(app, Uri.fromFile(malformed)));
                assertEquals(50, TagCatalog.installedRevision(db));
                assertEquals(TagStatus.AVOIDED, Queries.TagTable.getStatus(rule));
                byte[] badEscape = ("{\"TagRules\":[{\"idTag\":313,\"type\":3,\"status\":2,"
                    + "\"name\":\"invalid\\q\"}],\"TagReferences\":[]}").getBytes(StandardCharsets.UTF_8);
                File invalidEscape = archive(app, badEscape, 4);
                assertThrows(IOException.class, () -> BackupPreview.inspect(app, Uri.fromFile(invalidEscape)));
                assertThrows(IOException.class, () -> Importer.importData(app, Uri.fromFile(invalidEscape)));
                assertEquals(TagStatus.AVOIDED, Queries.TagTable.getStatus(rule));
                try (Cursor match = Queries.TagTable.getFilterCursor("%_", null, false, false)) {
                    assertTrue(match.moveToFirst());
                    assertEquals(rule.getId(), Queries.TagTable.cursorToTag(match).getId());
                }
                Queries.TagTable.updateTag(new Tag("matches only wildcards", 0, 313, TagType.TAG, TagStatus.AVOIDED));
                try (Cursor match = Queries.TagTable.getFilterCursor("%_", null, false, false)) {
                    assertEquals(1, match.getCount());
                }
            } finally {
                TagV2.updateMinCount(app, priorThreshold);
            }
        }
    }

    @Test public void legacyRuleWithUnqueryableLabelRemainsEditableAndRoundTrips() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            byte[] data = ("{\"Tags\":[{\"idTag\":311,\"name\":\"old \\\"quote\\\"\","
                + "\"type\":3,\"count\":-4,\"status\":2,\"online\":1}],"
                + "\"Bookmark\":[{\"tagId\":311,\"url\":\"fixture\",\"page\":0,\"type\":1}]}")
                .getBytes(StandardCharsets.UTF_8);
            Importer.importData(app, Uri.fromFile(archive(app, data, 3)));
            assertTrue(Queries.TagTable.getAllFiltered().isEmpty());
            assertEquals(0, Queries.TagTable.getTagById(311).getCount());
            Tag saved;
            try (Cursor rows = Queries.TagTable.getFilterCursor("", null, false, false)) {
                assertTrue(rows.moveToFirst());
                saved = Queries.TagTable.cursorToTag(rows);
            }
            assertEquals("old \"quote\"", saved.getName());
            assertEquals(TagStatus.AVOIDED, TagV2.updateStatus(saved));
            File exported = new File(app.getCacheDir(), "legacy-reexported.zip");
            Exporter.exportData(app, Uri.fromFile(exported));
            assertEquals(TagStatus.DEFAULT, TagV2.updateStatus(saved));
            Importer.importData(app, Uri.fromFile(exported));
            assertTrue(Queries.TagTable.getAllFiltered().isEmpty());
            try (Cursor rows = Queries.TagTable.getFilterCursor("", null, false, false)) {
                assertTrue(rows.moveToFirst());
                assertEquals(TagStatus.AVOIDED, Queries.TagTable.cursorToTag(rows).getStatus());
            }
        }
    }

    private static File archive(Application app, byte[] data, int version) throws Exception {
        File archive = new File(app.getCacheDir(), "tag-fixture.zip");
        JSONObject manifest = new JSONObject()
            .put("format_version", version)
            .put("database_length", data.length)
            .put("database_sha256", Exporter.sha256(data));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Database.json"));
            zip.write(data);
            zip.closeEntry();
        }
        return archive;
    }
}
