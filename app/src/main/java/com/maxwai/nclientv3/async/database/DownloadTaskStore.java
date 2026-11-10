// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.maxwai.nclientv3.async.downloader.DownloadQueue;
import com.maxwai.nclientv3.async.downloader.GalleryDownloaderManager;
import com.maxwai.nclientv3.async.downloader.GalleryDownloaderV2;
import com.maxwai.nclientv3.settings.Database;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class DownloadTaskStore {
    public static final String TABLE_NAME = "DownloadTasks";
    public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS DownloadTasks ("
        + "id_gallery INTEGER NOT NULL PRIMARY KEY CHECK(id_gallery > 0),"
        + "range_start INTEGER NOT NULL CHECK(range_start >= -1 AND range_start < 10000),"
        + "range_end INTEGER NOT NULL CHECK(range_end >= -1 AND range_end < 10000),"
        + "title TEXT NOT NULL, thumbnail TEXT, metadata TEXT, target_path TEXT, repair_pages TEXT,"
        + "state TEXT NOT NULL CHECK(state IN ('NOT_STARTED','DOWNLOADING','PAUSED','FAILED')),"
        + "failure TEXT NOT NULL, queue_order INTEGER NOT NULL,"
        + "revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),"
        + "metadata_attempts INTEGER NOT NULL DEFAULT 0 CHECK(metadata_attempts BETWEEN 0 AND 4),"
        + "page_number INTEGER NOT NULL DEFAULT -1 CHECK(page_number BETWEEN -1 AND 10000),"
        + "page_attempts INTEGER NOT NULL DEFAULT 0 CHECK(page_attempts BETWEEN 0 AND 4),"
        + "retry_at INTEGER NOT NULL DEFAULT 0 CHECK(retry_at >= 0));";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Object storageLock = new Object();
    private static final AtomicLong generation = new AtomicLong();

    private DownloadTaskStore() {}

    public static long generation() {
        return generation.get();
    }

    public static void execute(Runnable action) {
        executor.execute(action);
    }

    public static boolean saveNow(long epoch, ContentValues values) {
        synchronized (storageLock) {
            if (epoch != generation.get()) return false;
            SQLiteDatabase db = Database.getDatabase();
            if (db == null || !db.isOpen()) throw new IllegalStateException("Download task database is unavailable");
            Long revision = values.getAsLong("revision");
            if (revision != null) {
                try (Cursor cursor = db.query(TABLE_NAME, new String[]{"revision"}, "id_gallery=?", new String[]{values.getAsString("id_gallery")}, null, null, null)) {
                    if (cursor.moveToFirst() && cursor.getLong(0) > revision) return true;
                }
            }
            if (db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                throw new IllegalStateException("Unable to save download task");
            return true;
        }
    }

    public static void remove(long epoch, int id) {
        execute(() -> {
            synchronized (storageLock) {
                if (epoch != generation.get()) return;
                try {
                    Database.getDatabase().delete(TABLE_NAME, "id_gallery=?", new String[]{Integer.toString(id)});
                    Queries.DownloadTable.removeGallery(id);
                } catch (RuntimeException exception) {
                    LogUtility.e("Unable to remove download task", exception);
                }
            }
        });
    }

    public static List<GalleryDownloaderManager> load(Context context) {
        synchronized (storageLock) {
            return loadLocked(context);
        }
    }

    public static void loadIntoQueue(Context context) {
        synchronized (storageLock) {
            for (GalleryDownloaderManager task : loadLocked(context)) DownloadQueue.addRestored(task);
        }
    }

    private static List<GalleryDownloaderManager> loadLocked(Context context) {
        List<GalleryDownloaderManager> tasks = new ArrayList<>();
        try (Cursor cursor = Database.getDatabase().query(TABLE_NAME, null, null, null, null, null, "queue_order, id_gallery")) {
            while (cursor.moveToNext()) {
                try {
                    tasks.add(GalleryDownloaderManager.restore(context, cursor));
                } catch (RuntimeException exception) {
                    LogUtility.e("Unable to restore a download task", exception);
                }
            }
        }
        return tasks;
    }

    public static void migrateLegacy(SQLiteDatabase db) {
        try (Cursor tables = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='Downloads'", null)) {
            if (!tables.moveToFirst()) return;
        }
        boolean hasRangeStart = hasColumn(db, "Downloads", "range_start");
        boolean hasRangeEnd = hasColumn(db, "Downloads", "range_end");
        String rangeStart = hasRangeStart ? "range_start" : "-1";
        String rangeEnd = hasRangeEnd ? "range_end" : "-1";
        db.execSQL("INSERT OR IGNORE INTO DownloadTasks "
            + "(id_gallery,range_start,range_end,title,state,failure,queue_order) "
            + "SELECT Downloads.id_gallery," + rangeStart + "," + rangeEnd + ",COALESCE(title_pretty,''),'PAUSED','NONE',Downloads.id_gallery "
            + "FROM Downloads LEFT JOIN Gallery ON id_gallery=idGallery");
    }

    public static void addRepairColumns(SQLiteDatabase db) {
        if (!hasColumn(db, TABLE_NAME, "target_path")) db.execSQL("ALTER TABLE DownloadTasks ADD COLUMN target_path TEXT");
        if (!hasColumn(db, TABLE_NAME, "repair_pages")) db.execSQL("ALTER TABLE DownloadTasks ADD COLUMN repair_pages TEXT");
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(`" + table + "`)", null)) {
            int name = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) if (column.equals(cursor.getString(name))) return true;
        }
        return false;
    }

    public static void restore(Context context, ReadingProgress.RestoreAction action) throws IOException {
        synchronized (storageLock) {
            action.run();
            generation.incrementAndGet();
        }
        DownloadQueue.replaceAfterRestore(context);
    }
}
