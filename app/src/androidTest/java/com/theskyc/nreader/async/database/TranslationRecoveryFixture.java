package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class TranslationRecoveryFixture {
    private TranslationRecoveryFixture() {}

    public static File databaseFile(Context context, String token) {
        return new File(context.getFilesDir(), "translation-recovery-" + token + ".sqlite");
    }

    public static SQLiteDatabase createBaseline(File file, File scratchDirectory) throws IOException {
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            Queries.TagTable.createTables(database);
            TagTranslationCatalog.createTables(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical', 3, 10)");
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'canonical', 3, 1)");
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(payload(1, "before")),
                scratchDirectory, () -> false));
            return database;
        } catch (IOException | RuntimeException | AssertionError error) {
            database.close();
            throw error;
        }
    }

    public static SQLiteDatabase reopen(File file) {
        assertTrue("Interrupted phase did not leave its database", file.isFile());
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    }

    public static byte[] payload(int revision, String label) {
        return ("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":" + revision
            + ",\"entries\":[{\"id\":1,\"name\":\"" + label + "\",\"aliases\":[\"" + label + "-alias\"]}]}")
            .getBytes(StandardCharsets.UTF_8);
    }

    public static void assertInstalled(SQLiteDatabase database, int revision, String label) {
        assertEquals(revision, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEquals(label, DatabaseUtils.stringForQuery(database,
            "SELECT displayName FROM TagTranslations WHERE idTag = 1 AND locale = 'ja'", null));
        assertEquals(label + "-alias", DatabaseUtils.stringForQuery(database,
            "SELECT alias FROM TagTranslationAliases WHERE idTag = 1 AND locale = 'ja'", null));
        assertEquals("canonical", DatabaseUtils.stringForQuery(database, "SELECT name FROM Tags WHERE idTag = 1", null));
        assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT status FROM TagRules WHERE idTag = 1", null));
    }
}
