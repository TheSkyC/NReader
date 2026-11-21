// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DatabaseMigrationTest {
    @Test
    public void version22UpgradeCreatesTranslationTablesAndIndexes() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagTranslationAliases");
            database.execSQL("DROP TABLE TagTranslations");
            database.execSQL("DROP TABLE TagTranslationState");
            database.setVersion(22);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslations'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslationAliases'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslationState'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='TagTranslations_locale_key_idx'"));
        }
    }

    @Test
    public void version23UpgradeRebuildsTranslationSearchKeysWithoutChangingPackState() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('el', 7)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2')");
            database.setVersion(23);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(7, TagTranslationCatalog.installedRevision(database, "el"));
            assertEquals("\u03bf\u03c3", scalarString(database,
                "SELECT displayKey FROM TagTranslations WHERE idTag = 1 AND locale = 'el'"));
            assertEquals("\u03bf\u03c3", scalarString(database,
                "SELECT aliasKey FROM TagTranslationAliases WHERE idTag = 1 AND locale = 'el'"));
        }
    }

    @Test
    public void version21UpgradeCreatesTagSuggestionIndex() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP INDEX Tags_type_name_suggestion_idx");
            database.setVersion(21);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='Tags_type_name_suggestion_idx'"));
        }
    }

    @Test
    public void version20UpgradeSeparatesRulesAndDiscardsAccountFlags() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagRules");
            database.execSQL("DROP TABLE AccountTagBlacklist");
            database.execSQL("ALTER TABLE Tags ADD COLUMN status INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE Tags ADD COLUMN online INTEGER NOT NULL DEFAULT 0");
            database.execSQL("INSERT INTO Tags (idTag, name, type, count, status, online)"
                + " VALUES (132, 'saved', 3, 50, 2, 1), (133, 'blocked', 3, 40, 0, 1)");
            database.execSQL("INSERT INTO Gallery VALUES (100, 'fixture', '', '', 0, 1, '', 0, 0, 0, 0, 0)");
            Queries.FavoriteTable.insert(100);
            database.execSQL("INSERT INTO GalleryTags (id_gallery, id_tag) VALUES (100, 132)");
            database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('bundled', 43)");
            database.setVersion(20);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(43, TagCatalog.installedSeedRevision(database));
            assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getTagById(132).getStatus());
            assertEquals(TagStatus.DEFAULT, Queries.TagTable.getTagById(133).getStatus());
            assertEquals(1, Queries.GalleryBridgeTable.getTagsForGallery(100).getCount(TagType.TAG));
            try (Cursor rows = database.query("AccountTagBlacklist", null, null, null, null, null, null);
                 Cursor columns = database.query("Tags", null, "idTag = 132", null, null, null, null)) {
                assertEquals(0, rows.getCount());
                assertEquals(-1, columns.getColumnIndex("online"));
                assertEquals(-1, columns.getColumnIndex("status"));
            }
        }
    }
    @Test
    public void version19UpgradeKeepsUpstreamRevisionSeparateFromTheBundledRevision() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagCatalogState");
            database.execSQL("CREATE TABLE TagCatalogState (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)");
            database.execSQL("INSERT INTO TagCatalogState (id, revision) VALUES (1, 42)");
            database.setVersion(19);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(42, TagCatalog.installedRevision(database));
            assertEquals(0, TagCatalog.installedSeedRevision(database));
        }
    }

    @Test
    public void upgradingAfterDowngradeKeepsBothCatalogRevisions() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('legacy-upstream', 42), ('bundled', 43)");
            database.setVersion(19);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(42, TagCatalog.installedRevision(database));
            assertEquals(43, TagCatalog.installedSeedRevision(database));
        }
    }

    @Test
    public void version16UpgradeKeepsSearchBookmarks() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO Bookmark VALUES ('https://example.org/?q=test',2,0,0)");
            database.execSQL("DROP TABLE SearchPlans");
            database.setVersion(16);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM Bookmark WHERE page=2"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM SearchPlans"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
        }
    }

    @Test
    public void version13UpgradePreservesExistingRowsAndManualBookmark() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            legacy.execSQL(Queries.GalleryTable.CREATE_TABLE);
            legacy.execSQL(Queries.TagTable.CREATE_TABLE);
            legacy.execSQL(Queries.GalleryBridgeTable.CREATE_TABLE);
            legacy.execSQL(Queries.BookmarkTable.CREATE_TABLE);
            legacy.execSQL(Queries.DownloadTable.CREATE_TABLE);
            legacy.execSQL(Queries.HistoryTable.CREATE_TABLE);
            legacy.execSQL(Queries.FavoriteTable.CREATE_TABLE);
            legacy.execSQL(Queries.ResumeTable.CREATE_TABLE);
            legacy.execSQL(Queries.StatusTable.CREATE_TABLE);
            legacy.execSQL(Queries.StatusMangaTable.CREATE_TABLE);
            legacy.execSQL("INSERT INTO Gallery VALUES (123,'English','Japanese','Pretty',5,456,'jj',1234,100,100,50,50)");
            legacy.execSQL("INSERT INTO Favorite VALUES (123,99)");
            legacy.execSQL("INSERT INTO Resume VALUES (123,7)");
            legacy.execSQL("INSERT INTO Status VALUES ('Custom',12345)");
            legacy.execSQL("INSERT INTO StatusManga VALUES (123,'Custom',88)");
            legacy.execSQL("INSERT INTO Downloads VALUES (123,0,3)");
            legacy.execSQL("INSERT INTO History VALUES (123,456,'Title',0,77)");
            legacy.execSQL("INSERT INTO Bookmark VALUES ('https://example.org/',2,0,0)");
            legacy.setVersion(13);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(7, scalar(database, "SELECT page FROM Resume WHERE id_gallery=123"));
            assertEquals(99, scalar(database, "SELECT time FROM Favorite WHERE id_gallery=123"));
            assertEquals(12345, scalar(database, "SELECT color FROM Status WHERE name='Custom'"));
            assertEquals(88, scalar(database, "SELECT time FROM StatusManga WHERE gallery=123"));
            assertEquals(3, scalar(database, "SELECT range_end FROM Downloads WHERE id_gallery=123"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(0, scalar(database, "SELECT range_start FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(3, scalar(database, "SELECT range_end FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM DownloadTasks WHERE state='PAUSED'"));
            assertEquals(77, scalar(database, "SELECT time FROM History WHERE id=123"));
            assertEquals(2, scalar(database, "SELECT page FROM Bookmark"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM SearchPlans"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
        }
    }

    @Test
    public void version17UpgradePreservesReadingProgressAndCreatesReaderBookmarks() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO ReadingProgress VALUES (123,7,0.5,456)");
            database.execSQL("DROP TABLE ReaderBookmarks");
            database.setVersion(17);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(24, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM ReadingProgress " +
                "WHERE id_gallery=123 AND page=7 AND offset=0.5 AND updated_at=456"));
            database.execSQL("INSERT INTO ReaderBookmarks (id_gallery,page,title,created_at,updated_at) " +
                "VALUES (123,7,'Page 7',456,456)");
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks WHERE id_gallery=123 AND page=7"));
        }
    }

    @Test
    public void version12CreatesStatusesBeforeInitializingThem() {
        Context context = RuntimeEnvironment.getApplication();
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            new DatabaseHelper(context).onUpgrade(database, 12, 16);
            assertEquals(6, scalar(database, "SELECT COUNT(*) FROM Status"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
        }
    }

    private int scalar(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private String scalarString(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }

    @Test
    public void olderUpgradePathsDoNotRecreateExistingColumnsOrQueryFutureTables() {
        Context context = RuntimeEnvironment.getApplication();
        for (int version : new int[]{6, 8}) {
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
            try (SQLiteDatabase legacy = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
                legacy.execSQL(Queries.GalleryTable.CREATE_TABLE);
                legacy.execSQL("ALTER TABLE Gallery ADD COLUMN favorite INT NOT NULL DEFAULT 0");
                legacy.execSQL(Queries.TagTable.CREATE_TABLE);
                legacy.execSQL(Queries.GalleryBridgeTable.CREATE_TABLE);
                legacy.execSQL(Queries.BookmarkTable.CREATE_TABLE);
                if (version == 8) {
                    legacy.execSQL("CREATE TABLE Downloads (id_gallery INT NOT NULL PRIMARY KEY)");
                    legacy.execSQL(Queries.HistoryTable.CREATE_TABLE);
                }
                legacy.setVersion(version);
            }
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                assertEquals(24, database.getVersion());
                assertEquals(0, scalar(database, "SELECT COUNT(time) FROM Favorite"));
                assertEquals(0, scalar(database, "SELECT COUNT(range_start) FROM Downloads"));
                assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
                assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
            }
        }
    }
}
