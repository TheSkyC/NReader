package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;

public final class TagDatabaseFixture {
    private TagDatabaseFixture() {}

    public static void createTables(SQLiteDatabase database) {
        Queries.TagTable.createTables(database);
    }
}
