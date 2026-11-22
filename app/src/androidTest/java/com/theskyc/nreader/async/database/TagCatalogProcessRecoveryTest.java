package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.TagRecoveryProcess;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class TagCatalogProcessRecoveryTest {
    private static final String BASELINE = "[[1,\"before-1\",10,1],[2,\"before-2\",10,2],"
        + "[3,\"before-3\",10,3],[4,\"before-4\",10,4],[5,\"before-5\",10,5],"
        + "[6,\"before-6\",10,6],[7,\"before-7\",10,7]]";
    private static final String CANDIDATE = "[[1,\"after-1\",20,1],[2,\"after-2\",20,2],"
        + "[3,\"after-3\",20,3],[4,\"after-4\",20,4],[5,\"after-5\",20,5],"
        + "[6,\"after-6\",20,6],[7,\"after-7\",20,7]]";

    @Test
    public void holdsCatalogTransactionForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("crash");
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile(token), null)) {
            Queries.TagTable.createTables(database);
            database.execSQL(TagCatalog.CREATE_STATE);
            assertTrue(TagCatalog.apply(database, input(BASELINE), 1, 7, () -> false));
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'before-1', 1, 1)");

            TagCatalog.apply(database, input(CANDIDATE), 2, 7, () -> {
                if (database.inTransaction() && TagCatalog.installedRevision(database) == 2) {
                    assertInstalled(database, 2, "after-", 20);
                    TagRecoveryProcess.awaitExternalStop(token);
                }
                return false;
            });
            fail("The catalog update should remain inside its uncommitted transaction");
        }
    }

    @Test
    public void previousCatalogAndLocalRuleSurviveProcessDeath() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        File databaseFile = databaseFile(token);
        assertTrue("Crash phase did not leave its database", databaseFile.isFile());
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            assertInstalled(database, 1, "before-", 10);
            assertTrue(TagCatalog.apply(database, input(CANDIDATE), 2, 7, () -> false));
            assertInstalled(database, 2, "after-", 20);
        }
    }

    private static File databaseFile(String token) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new File(context.getFilesDir(), "catalog-recovery-" + token + ".sqlite");
    }

    private static void assertInstalled(SQLiteDatabase database, int revision, String prefix, int count) {
        assertEquals(revision, TagCatalog.installedRevision(database));
        assertEquals(7, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null));
        assertEquals(7, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags WHERE idTag BETWEEN 1 AND 7"
            + " AND type = idTag AND name = (? || idTag) AND count = ?", new String[]{prefix, Integer.toString(count)}));
        assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagRules"
            + " WHERE idTag = 1 AND type = 1 AND name = 'before-1' AND status = 1", null));
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
