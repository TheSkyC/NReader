package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagCatalogTest {
    private static final String OTHER_TYPES = ",[2,\"character\",10,2],[3,\"tag\",12,3],"
        + "[4,\"artist\",8,4],[5,\"group\",9,5],[6,\"language\",6,6],[7,\"category\",3,7]";
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        database.execSQL(TagCatalog.CREATE_STATE);
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void refreshUpdatesMetadataWhilePreservingRulesBlacklistAndOmittedIds() throws IOException {
        Tag parody = new Tag("old", 10, 1, TagType.PARODY, TagStatus.AVOIDED);
        Tag omitted = new Tag("omitted", 10, 42, TagType.TAG, TagStatus.ACCEPTED);
        Queries.TagTable.insert(parody);
        Queries.TagTable.insert(omitted);
        Queries.TagTable.updateTag(parody);
        Queries.TagTable.updateTag(omitted);
        Queries.TagTable.updateBlacklistedTag(parody, true);

        assertTrue(apply(snapshot("renamed"), 10));
        Tag updated = Queries.TagTable.getTagById(1);
        assertEquals("renamed", updated.getName());
        assertEquals(11, updated.getCount());
        assertEquals(TagStatus.AVOIDED, updated.getStatus());
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.getStatus(parody));
        assertTrue(Queries.TagTable.isBlackListed(updated));
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.getTagById(3).getStatus());
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getTagById(42).getStatus());
        assertEquals(10, TagCatalog.installedRevision(database));

        parody.setStatus(TagStatus.ACCEPTED);
        assertEquals(1, Queries.TagTable.updateTag(parody));
        assertEquals(11, Queries.TagTable.getTagById(1).getCount());
        assertTrue(apply(snapshot("next"), 11));
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getTagById(1).getStatus());
        assertTrue(Queries.TagTable.isBlackListed(updated));
    }

    @Test public void schemaConstrainsRuleIdentityStatusAndAccountTagIds() {
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'rule', 3, 1)");
        assertThrows(SQLiteConstraintException.class,
            () -> database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'other type', 4, 2)"));
        assertThrows(SQLiteConstraintException.class,
            () -> database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (2, 'bad status', 3, 0)"));
        assertThrows(SQLiteConstraintException.class,
            () -> database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (0, 'bad id', 3, 1)"));
        assertThrows(SQLiteConstraintException.class,
            () -> database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (0)"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM TagRules"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM AccountTagBlacklist"));
    }

    @Test public void canonicalCatalogCanReplaceProvisionalBackupMetadata() throws IOException {
        database.execSQL("INSERT INTO Tags (idTag, name, type, count, provisional) VALUES (1, 'backup', 3, 5, 1)");
        Tag priorRule = new Tag("backup", 5, 1, TagType.TAG, TagStatus.AVOIDED);
        Queries.TagTable.updateTag(priorRule);
        assertTrue(apply(snapshot("canonical"), 10));
        assertEquals("canonical", Queries.TagTable.getTagById(1).getName());
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.getTagById(1).getStatus());
        assertTrue(Queries.TagTable.getAllFiltered().isEmpty());
        try (android.database.Cursor rules = Queries.TagTable.getFilterCursor("backup", null, false, true)) {
            assertTrue(rules.moveToFirst());
            Tag editable = Queries.TagTable.cursorToTag(rules);
            assertEquals(TagStatus.AVOIDED, editable.getStatus());
            editable.setStatus(TagStatus.DEFAULT);
            assertEquals(1, Queries.TagTable.updateTag(editable));
        }
        try (android.database.Cursor rules = Queries.TagTable.getFilterCursor("", null, false, true)) {
            assertEquals(0, rules.getCount());
        }
    }

    @Test public void snapshotCanResolvePreviouslyUnknownType() throws IOException {
        Queries.TagTable.insert(new Tag("unknown type", 5, 1, TagType.UNKNOWN, TagStatus.DEFAULT));
        assertTrue(apply(snapshot("known type"), 10));
        assertEquals(TagType.PARODY, Queries.TagTable.getTagById(1).getType());
    }

    @Test public void rejectsMalformedDuplicateIncompleteAndConflictingSnapshotsAtomically() throws IOException {
        assertTrue(apply(snapshot("installed"), 10));
        String prefix = "[[1,\"replacement\",11,1]" + OTHER_TYPES + ",[80,\"new\",0,3]";
        for (String bad : new String[]{
            prefix + ",[81,\"bad\",-1,3]]",
            prefix + ",[81,null,4,3]]",
            prefix + ",[81,\"bad\",\"4\",3]]",
            prefix + ",[81,\"bad\",4,8]]",
            prefix + ",[81,\"bad\",4,3,0]]",
            prefix + ",[81,\"bad\\q\",4,3]]",
            prefix + ",[81,\"bad\\u+061\",4,3]]",
            prefix + ",[81,\"bad\nname\",4,3]]",
            prefix + ",[81,\"quote\\\"\",4,3]]",
            prefix + ",[81,\"backslash\\\\\",4,3]]",
            prefix + ",[80,\"duplicate\",4,3]]",
            prefix,
            prefix + "] []",
            "[[1,\"incomplete\",0,1]]",
            "[[1,\"type conflict\",11,4],[8,\"parody\",4,1]" + OTHER_TYPES + "]"
        }) {
            assertThrows(IOException.class, () -> apply(bad, 11));
            assertEquals("installed", Queries.TagTable.getTagById(1).getName());
            assertNull(Queries.TagTable.getTagById(80));
            assertEquals(10, TagCatalog.installedRevision(database));
        }
        assertTrue(apply(snapshot("recovered"), 11));
    }

    @Test public void acceptsValidUnicodeAndSlashEscapes() throws IOException {
        assertTrue(apply(snapshot("escaped\\u0020\\u65e5\\/name"), 10));
        assertEquals("escaped 日/name", Queries.TagTable.getTagById(1).getName());
    }

    @Test public void cancellationBeforeCommitRollsBackRowsAndRevision() throws IOException {
        assertTrue(apply(snapshot("installed"), 10));
        assertThrows(InterruptedIOException.class,
            () -> apply(snapshot("cancelled"), 11, () -> TagCatalog.installedRevision(database) == 11));
        assertEquals("installed", Queries.TagTable.getTagById(1).getName());
        assertEquals(10, TagCatalog.installedRevision(database));
        assertTrue(apply(snapshot("recovered"), 11));
    }

    @Test public void equalAndOlderRevisionsCannotReplaceCurrentRows() throws IOException {
        assertTrue(apply(snapshot("current"), 10));
        assertFalse(apply(snapshot("equal"), 10));
        assertFalse(apply(snapshot("older"), 9));
        assertEquals("current", Queries.TagTable.getTagById(1).getName());
        assertEquals(10, TagCatalog.installedRevision(database));
    }

    @Test public void bundledRevisionsAdvanceUntilAValidatedRemoteCatalogIsInstalled() throws IOException {
        assertTrue(applyBundled("seed", 40));
        assertFalse(applyBundled("older seed", 39));
        assertTrue(applyBundled("updated seed", 41));
        assertEquals(41, TagCatalog.installedSeedRevision(database));
        assertEquals(0, TagCatalog.installedRevision(database));
        assertEquals("updated seed", Queries.TagTable.getTagById(1).getName());

        assertTrue(apply(snapshot("remote"), 10));
        assertFalse(applyBundled("newer seed", 42));
        assertEquals("remote", Queries.TagTable.getTagById(1).getName());
        assertEquals(10, TagCatalog.installedRevision(database));
    }

    private boolean applyBundled(String name, int revision) throws IOException {
        return TagCatalog.applyBundled(database, new ByteArrayInputStream(snapshot(name).getBytes(StandardCharsets.UTF_8)),
            revision, 7, () -> false);
    }

    private static String snapshot(String firstName) {
        return "[[1,\"" + firstName + "\",11,1]" + OTHER_TYPES + "]";
    }

    private boolean apply(String json, int revision) throws IOException {
        return apply(json, revision, () -> false);
    }

    private boolean apply(String json, int revision, BooleanSupplier cancelled) throws IOException {
        return TagCatalog.apply(database, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
            revision, 7, cancelled);
    }

    private int scalar(String sql) {
        try (android.database.Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }
}
