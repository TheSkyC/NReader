package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagCatalogStagingTest {
    private SQLiteDatabase database;

    @Before public void createDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        database.execSQL(TagCatalog.CREATE_STATE);
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void checksRevisionAgainAfterPreparingInput() throws Exception {
        try (InputStream initial = new FilterInputStream(snapshot("older", 7)) {
            private boolean committed;

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!committed) {
                    committed = true;
                    assertFalse(database.inTransaction());
                    assertTrue(TagCatalog.apply(database, snapshot("newer", 7), 3, 7, () -> false));
                }
                return super.read(buffer, offset, length);
            }
        }) {
            assertFalse(TagCatalog.apply(database, initial, 2, 7, () -> false));
        }
        assertEquals(3, TagCatalog.installedRevision(database));
        assertEquals("newer", Queries.TagTable.getTagById(1).getName());
    }

    @Test public void importsAcrossBatchBoundariesAndRejectsDuplicateAcrossBatches() throws Exception {
        assertTrue(TagCatalog.apply(database, snapshot("first", 260), 1, 7, () -> false));
        assertEquals(260, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null));
        for (int id : new int[]{127, 128, 129, 256, 257, 260}) {
            assertEquals("name-\uD83D\uDE80-" + id, Queries.TagTable.getTagById(id).getName());
        }
        String duplicate = new String(snapshot("changed", 260).readAllBytes(), StandardCharsets.UTF_8)
            .replace("[260,", "[1,");
        IOException failure = assertThrows(IOException.class,
            () -> TagCatalog.apply(database, bytes(duplicate), 2, 7, () -> false));
        assertEquals("Duplicate tag ID", failure.getMessage());
        assertEquals(1, TagCatalog.installedRevision(database));
        assertEquals("first", Queries.TagTable.getTagById(1).getName());
    }

    @Test public void oversizedWhitespaceIsRejectedBeforeAnyRowsAreCommitted() {
        InputStream padding = new InputStream() {
            private int bytesLeft = TagCatalogManifest.MAX_PAYLOAD_BYTES;

            @Override public int read() {
                return bytesLeft-- > 0 ? ' ' : -1;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (bytesLeft <= 0) return -1;
                int count = Math.min(length, bytesLeft);
                Arrays.fill(buffer, offset, offset + count, (byte) ' ');
                bytesLeft -= count;
                return count;
            }
        };
        IOException failure = assertThrows(IOException.class, () -> TagCatalog.apply(database,
            new SequenceInputStream(snapshot("first", 7), padding), 1, 7, () -> false));
        assertEquals("Tag catalog exceeds size limit", failure.getMessage());
        assertEquals(0, TagCatalog.installedRevision(database));
        assertEquals(0, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null));
    }

    @Test public void updatesOnlyChangedOrProvisionalRowsAndStillAdvancesIdenticalRevision() throws Exception {
        String baseline = new String(snapshot("first", 260).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(TagCatalog.apply(database, bytes(baseline), 1, 7, () -> false));
        database.execSQL("UPDATE Tags SET provisional = 1 WHERE idTag = 259");
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'first', 1, 1)");
        database.execSQL("CREATE TABLE ChangedTagRows (idTag INTEGER PRIMARY KEY)");
        database.execSQL("CREATE TRIGGER audit_catalog_updates AFTER UPDATE ON Tags"
            + " BEGIN INSERT INTO ChangedTagRows VALUES (new.idTag); END");

        String refreshed = baseline.replace("[1,\"first\",1,1]", "[1,\"First\",1,1]")
            .replace("[258,\"name-\uD83D\uDE80-258\",258,7]", "[258,\"name-\uD83D\uDE80-258\",999,7]");
        assertTrue(TagCatalog.apply(database, bytes(refreshed), 2, 7, () -> false));
        assertEquals(3, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM ChangedTagRows", null));
        assertEquals(3, DatabaseUtils.longForQuery(database,
            "SELECT COUNT(*) FROM ChangedTagRows WHERE idTag IN (1, 258, 259)", null));
        assertEquals("First", Queries.TagTable.getTagById(1).getName());
        assertEquals(999, Queries.TagTable.getTagById(258).getCount());
        assertEquals(0, DatabaseUtils.longForQuery(database, "SELECT provisional FROM Tags WHERE idTag = 259", null));
        assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT status FROM TagRules WHERE idTag = 1", null));

        assertTrue(TagCatalog.apply(database, bytes(refreshed), 3, 7, () -> false));
        assertEquals(3, TagCatalog.installedRevision(database));
        assertEquals(3, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM ChangedTagRows", null));
    }

    private static ByteArrayInputStream snapshot(String name, int count) {
        StringBuilder json = new StringBuilder("[[1,\"").append(name).append("\",1,1]");
        for (int id = 2; id <= count; id++) {
            json.append(",[").append(id).append(",\"name-\uD83D\uDE80-").append(id)
                .append("\",").append(id).append(',').append(Math.min(id, 7)).append(']');
        }
        return bytes(json.append(']').toString());
    }

    private static ByteArrayInputStream bytes(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
