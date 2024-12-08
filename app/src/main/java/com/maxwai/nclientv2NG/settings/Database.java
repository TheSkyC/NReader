package com.maxwai.nclientv2NG.settings;

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.maxwai.nclientv2NG.async.database.Queries;
import com.maxwai.nclientv2NG.utility.LogUtility;

public class Database {
    @NonNull
    private static SQLiteDatabase database;

    @NonNull
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
