// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.settings.Database;

import java.util.ArrayList;
import java.util.List;

/** Stores reusable tag-manager filters in the main application database. */
public final class TagViewStore {
    public static final String TABLE_NAME = "TagViews";
    public static final int DEFAULT_MIN_COUNT = 25;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_QUERY_LENGTH = 8192;

    public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS TagViews ("
        + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, query TEXT NOT NULL,"
        + "tag_type INTEGER NOT NULL CHECK(tag_type BETWEEN 1 AND 5),"
        + "min_count INTEGER NOT NULL CHECK(min_count >= 0),"
        + "sort_by_name INTEGER NOT NULL CHECK(sort_by_name IN (0,1)),"
        + "status_filter INTEGER NOT NULL CHECK(status_filter BETWEEN -1 AND 2),"
        + "translated_only INTEGER NOT NULL CHECK(translated_only IN (0,1)),"
        + "pinned INTEGER NOT NULL CHECK(pinned IN (0,1)));";

    private TagViewStore() {}

    public static boolean isValid(ContentValues values, boolean requireId) {
        String name = values.getAsString("name");
        String query = values.getAsString("query");
        Long type = values.getAsLong("tag_type");
        Long minCount = values.getAsLong("min_count");
        Long sortByName = values.getAsLong("sort_by_name");
        Long statusFilter = values.getAsLong("status_filter");
        Long translatedOnly = values.getAsLong("translated_only");
        Long pinned = values.getAsLong("pinned");
        Long id = values.getAsLong("id");
        return (!requireId || id != null && id > 0)
            && name != null && !name.trim().isEmpty() && name.length() <= MAX_NAME_LENGTH
            && query != null && query.length() <= MAX_QUERY_LENGTH
            && type != null && type >= 1 && type <= 5
            && minCount != null && minCount >= 0 && minCount <= Integer.MAX_VALUE
            && isBoolean(sortByName) && statusFilter != null && statusFilter >= -1 && statusFilter <= 2
            && isBoolean(translatedOnly) && isBoolean(pinned);
    }

    public static long insert(ContentValues values) {
        if (values.containsKey("id") || !isValid(values, false))
            throw new IllegalArgumentException("Invalid tag view");
        return database().insertOrThrow(TABLE_NAME, null, values);
    }

    public static boolean update(long id, ContentValues values) {
        if (id <= 0 || values.containsKey("id") || !isValid(values, false))
            throw new IllegalArgumentException("Invalid tag view");
        return database().update(TABLE_NAME, values, "id=?", new String[]{Long.toString(id)}) == 1;
    }

    public static boolean delete(long id) {
        return id > 0 && database().delete(TABLE_NAME, "id=?", new String[]{Long.toString(id)}) == 1;
    }

    public static List<ContentValues> list() {
        List<ContentValues> views = new ArrayList<>();
        try (Cursor cursor = database().query(TABLE_NAME, null, null, null, null, null,
            "pinned DESC, id ASC")) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                android.database.DatabaseUtils.cursorRowToContentValues(cursor, values);
                views.add(values);
            }
        }
        return views;
    }

    private static boolean isBoolean(Long value) {
        return value != null && (value == 0 || value == 1);
    }

    private static SQLiteDatabase database() {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null || !db.isOpen()) throw new IllegalStateException("Tag view database is unavailable");
        return db;
    }
}
