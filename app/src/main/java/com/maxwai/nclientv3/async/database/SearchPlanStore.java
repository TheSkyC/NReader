// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.maxwai.nclientv3.settings.Database;

import java.util.ArrayList;
import java.util.List;

public final class SearchPlanStore {
    public static final String TABLE_NAME = "SearchPlans";
    public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS SearchPlans ("
        + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, query TEXT NOT NULL,"
        + "sort TEXT NOT NULL, page INTEGER NOT NULL CHECK(page > 0),"
        + "pinned INTEGER NOT NULL CHECK(pinned IN (0,1)));";

    private SearchPlanStore() {}

    public static boolean isValid(ContentValues values, boolean requireId) {
        String name = values.getAsString("name");
        String query = values.getAsString("query");
        String sort = values.getAsString("sort");
        Long page = values.getAsLong("page");
        Long pinned = values.getAsLong("pinned");
        Long id = values.getAsLong("id");
        return (!requireId || id != null && id > 0) && name != null && !name.trim().isEmpty()
            && name.length() <= 200 && query != null && query.length() <= 8192
            && sort != null && (sort.isEmpty() || sort.equals("popular") || sort.equals("popular-week")
                || sort.equals("popular-today") || sort.equals("popular-month"))
            && page != null && page > 0 && page <= Integer.MAX_VALUE
            && pinned != null && (pinned == 0 || pinned == 1);
    }

    public static long insert(ContentValues values) {
        if (values.containsKey("id") || !isValid(values, false)) throw new IllegalArgumentException("Invalid search plan");
        return database().insertOrThrow(TABLE_NAME, null, values);
    }

    public static boolean update(long id, ContentValues values) {
        if (id <= 0 || values.containsKey("id") || !isValid(values, false))
            throw new IllegalArgumentException("Invalid search plan");
        return database().update(TABLE_NAME, values, "id=?", new String[]{Long.toString(id)}) == 1;
    }

    public static boolean delete(long id) {
        return database().delete(TABLE_NAME, "id=?", new String[]{Long.toString(id)}) == 1;
    }

    public static List<ContentValues> list() {
        List<ContentValues> plans = new ArrayList<>();
        try (Cursor cursor = database().query(TABLE_NAME, null, null, null, null, null, "pinned DESC, id ASC")) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                android.database.DatabaseUtils.cursorRowToContentValues(cursor, values);
                plans.add(values);
            }
        }
        return plans;
    }

    private static SQLiteDatabase database() {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null || !db.isOpen()) throw new IllegalStateException("Search plan database is unavailable");
        return db;
    }
}
