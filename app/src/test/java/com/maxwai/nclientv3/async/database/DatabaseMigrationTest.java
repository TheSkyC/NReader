// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
            assertEquals(17, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM Bookmark WHERE page=2"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM SearchPlans"));
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
            assertEquals(17, database.getVersion());
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
            assertEquals(17, database.getVersion());
                assertEquals(0, scalar(database, "SELECT COUNT(time) FROM Favorite"));
                assertEquals(0, scalar(database, "SELECT COUNT(range_start) FROM Downloads"));
                assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
            }
        }
    }
}
