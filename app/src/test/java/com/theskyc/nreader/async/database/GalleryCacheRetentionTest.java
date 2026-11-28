package com.theskyc.nreader.async.database;

import android.app.Application;
import android.content.ContentValues;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.components.classes.ReadingPosition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryCacheRetentionTest {
    private SQLiteDatabase database;

    @Before public void setUp() {
        database = SQLiteDatabase.create(null);
        database.execSQL(Queries.GalleryTable.CREATE_TABLE);
        database.execSQL(Queries.TagTable.CREATE_TABLE);
        database.execSQL(Queries.GalleryBridgeTable.CREATE_TABLE);
        database.execSQL(Queries.DownloadTable.CREATE_TABLE);
        database.execSQL(Queries.FavoriteTable.CREATE_TABLE);
        database.execSQL(Queries.HistoryTable.CREATE_TABLE);
        database.execSQL(Queries.StatusTable.CREATE_TABLE);
        database.execSQL(Queries.StatusMangaTable.CREATE_TABLE);
        database.execSQL(Queries.ReaderBookmarkTable.CREATE_TABLE);
        database.execSQL(Queries.ReadingProgressTable.CREATE_TABLE);
        Queries.setDb(database);
    }

    @After public void tearDown() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void clearGalleriesKeepsBookmarksAndProgressWithoutGalleryRows() {
        insertGallery(1);
        insertGallery(2);
        insertHistory(1);
        Queries.ReaderBookmarkTable.upsert(2, 1, "Saved page", "Note");
        Queries.ReaderBookmarkTable.upsert(3, 1, "Already orphaned", "Note");
        Queries.ReadingProgressTable.insert(2, new ReadingPosition(2, 0.25f));

        Queries.GalleryTable.clearGalleries();

        assertEquals(0, count(Queries.GalleryTable.TABLE_NAME, Queries.GalleryTable.IDGALLERY, 1));
        assertEquals(0, count(Queries.GalleryTable.TABLE_NAME, Queries.GalleryTable.IDGALLERY, 2));
        assertEquals(1, count(Queries.HistoryTable.TABLE_NAME, Queries.HistoryTable.ID, 1));
        assertEquals(1, count(Queries.ReaderBookmarkTable.TABLE_NAME, "id_gallery", 2));
        assertEquals(1, count(Queries.ReaderBookmarkTable.TABLE_NAME, "id_gallery", 3));
        assertEquals(1, count(Queries.ReadingProgressTable.TABLE_NAME, "id_gallery", 2));
    }

    @Test public void deletingUnavailableGalleryKeepsItsReaderBookmarks() {
        insertGallery(4);
        Queries.ReaderBookmarkTable.upsert(4, 2, "Saved page", "Note");

        Queries.GalleryTable.delete(4);

        assertEquals(0, count(Queries.GalleryTable.TABLE_NAME, Queries.GalleryTable.IDGALLERY, 4));
        assertEquals(1, count(Queries.ReaderBookmarkTable.TABLE_NAME, "id_gallery", 4));
    }

    private void insertGallery(int id) {
        ContentValues values = new ContentValues(12);
        values.put(Queries.GalleryTable.IDGALLERY, id);
        values.put(Queries.GalleryTable.TITLE_ENG, "Title " + id);
        values.put(Queries.GalleryTable.TITLE_JP, "Title " + id);
        values.put(Queries.GalleryTable.TITLE_PRETTY, "Title " + id);
        values.put(Queries.GalleryTable.FAVORITE_COUNT, 0);
        values.put(Queries.GalleryTable.MEDIAID, 1);
        values.put(Queries.GalleryTable.PAGES, "");
        values.put(Queries.GalleryTable.UPLOAD, 0L);
        values.put(Queries.GalleryTable.MAX_WIDTH, 0);
        values.put(Queries.GalleryTable.MAX_HEIGHT, 0);
        values.put(Queries.GalleryTable.MIN_WIDTH, 0);
        values.put(Queries.GalleryTable.MIN_HEIGHT, 0);
        assertTrue(database.insert(Queries.GalleryTable.TABLE_NAME, null, values) > 0);
    }

    private void insertHistory(int id) {
        ContentValues values = new ContentValues(5);
        values.put(Queries.HistoryTable.ID, id);
        values.put(Queries.HistoryTable.MEDIAID, 1);
        values.put(Queries.HistoryTable.TITLE, "Title " + id);
        values.put(Queries.HistoryTable.THUMB, "https://example.test/thumb.jpg");
        values.put(Queries.HistoryTable.TIME, 1L);
        assertTrue(database.insert(Queries.HistoryTable.TABLE_NAME, null, values) > 0);
    }

    private long count(String table, String column, int id) {
        return DatabaseUtils.longForQuery(database,
            "SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = ?",
            new String[]{Integer.toString(id)});
    }
}
