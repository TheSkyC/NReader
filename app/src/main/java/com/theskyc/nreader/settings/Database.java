package com.theskyc.nreader.settings;

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.Nullable;

import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.utility.LogUtility;

public class Database {
    private static SQLiteDatabase database;

    @Nullable
    public static SQLiteDatabase getDatabase() {
        return database;
    }

    public static void setDatabase(SQLiteDatabase database) {
        Database.database = database;
        LogUtility.d("SETTED database" + database);
        setDBForTables(database);
        Queries.StatusTable.initStatuses();
    }

    private static void setDBForTables(SQLiteDatabase database) {
        Queries.setDb(database);
    }

}
