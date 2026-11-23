// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DatabaseMigrationTest {
    @Test
    public void translationRemovalGenerationSurvivesDatabaseReopen() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        byte[] pack = ("{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
            + "\"entries\":[{\"id\":700004,\"name\":\"name\"}]}").getBytes(StandardCharsets.UTF_8);
        long beforeRemoval;
        TagTranslationCatalog.PackState oldSelection;
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700004, 'fixture', 3, 1)");
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack), context.getCacheDir(), () -> false));
            beforeRemoval = TagTranslationCatalog.generationSnapshot(database);
            oldSelection = TagTranslationCatalog.installedPackStates(database).get("ja");
            assertTrue(TagTranslationCatalog.removeIfUnchanged(database, "ja", oldSelection));
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertTrue(TagTranslationCatalog.generationSnapshot(database) > beforeRemoval);
            assertThrows(TagTranslationCatalog.ChangedDuringImportException.class, () ->
                TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack), context.getCacheDir(), beforeRemoval, () -> false));
            assertEquals(0, TagTranslationCatalog.installedRevision(database, "ja"));
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack), context.getCacheDir(), () -> false));
            assertFalse(TagTranslationCatalog.removeIfUnchanged(database, "ja", oldSelection));
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        }
    }

    @Test
    public void version24UpgradePreservesInstalledTranslations() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion25AliasTable(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 3)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (1, 'ja', 'name', 'name')");
            database.execSQL("DROP TABLE TagTranslationGenerations");
            database.setVersion(24);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(3, TagTranslationCatalog.installedRevision(database, "ja"));
            assertEquals("name", scalarString(database, "SELECT displayName FROM TagTranslations WHERE locale='ja'"));
            assertEquals(0, TagTranslationCatalog.generationSnapshot(database));
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            assertTrue(TagTranslationCatalog.removeIfUnchanged(database, "ja", state));
            assertEquals(1, TagTranslationCatalog.generationSnapshot(database));
        }
    }

    @Test
    public void version22UpgradeCreatesTranslationTablesAndIndexes() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagTranslationAliases");
            database.execSQL("DROP TABLE TagTranslations");
            database.execSQL("DROP TABLE TagTranslationState");
            database.setVersion(22);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslations'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslationAliases'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslationState'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='TagTranslations_locale_key_idx'"));
        }
    }

    @Test
    public void version23UpgradeCollapsesEquivalentAliasesWithoutChangingOtherPackData() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion25AliasTable(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('el', 7)");
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 5)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2'), (1, 'el', '\u03bf\u03c3', '\u03bf\u03c3'),"
                + " (2, 'el', '\u03bf\u03c3', '\u03bf\u03c3'), (1, 'ja', '\u03bf\u03c3', '\u03bf\u03c3')");
            database.setVersion(23);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(7, TagTranslationCatalog.installedRevision(database, "el"));
            assertEquals(5, TagTranslationCatalog.installedRevision(database, "ja"));
            assertEquals("\u03bf\u03c3", scalarString(database,
                "SELECT displayKey FROM TagTranslations WHERE idTag = 1 AND locale = 'el'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagTranslations"
                + " WHERE idTag = 1 AND locale = 'el' AND displayName = '\u039f\u03a3'"));
            assertEquals(3, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases WHERE aliasKey = '\u03bf\u03c3'"));
            assertEquals("\u039f\u03a3", scalarString(database,
                "SELECT alias FROM TagTranslationAliases WHERE idTag = 1 AND locale = 'el'"));
        }
    }

    @Test
    public void failedVersion23UpgradeRollsBackAndSucceedsAfterFailureIsRemoved() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion25AliasTable(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('el', 7)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " VALUES (1, 'el', '\u039f\u03a3', '\u03bf\u03c2'),"
                + " (1, 'el', '\u03bf\u03c3', '\u03bf\u03c3')");
            database.execSQL("CREATE TRIGGER fail_alias_key_upgrade BEFORE UPDATE OF aliasKey"
                + " ON TagTranslationAliases BEGIN SELECT RAISE(ABORT, 'synthetic migration failure'); END");
            database.setVersion(23);
        }

        assertThrows(SQLiteException.class, () -> {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                helper.getWritableDatabase();
            }
        });

        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(23, database.getVersion());
            assertEquals("\u03bf\u03c2", scalarString(database,
                "SELECT displayKey FROM TagTranslations WHERE idTag = 1 AND locale = 'el'"));
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases"
                + " WHERE idTag = 1 AND locale = 'el'"));
            assertEquals(7, scalar(database, "SELECT revision FROM TagTranslationState WHERE locale = 'el'"));
            database.execSQL("DROP TRIGGER fail_alias_key_upgrade");
        }

        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(7, TagTranslationCatalog.installedRevision(database, "el"));
            assertEquals("\u039f\u03a3", scalarString(database,
                "SELECT displayName FROM TagTranslations WHERE idTag = 1 AND locale = 'el'"));
            assertEquals("\u03bf\u03c3", scalarString(database,
                "SELECT displayKey FROM TagTranslations WHERE idTag = 1 AND locale = 'el'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases"
                + " WHERE idTag = 1 AND locale = 'el' AND aliasKey = '\u03bf\u03c3'"));
        }
    }

    @Test
    public void version25UpgradeMarksOldAliasOrderUnknownAndPreservesRows() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion25AliasTable(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700004, 'fixture', 3, 1)");
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (700004, 'ja', 'name', 'name')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " VALUES (700004, 'ja', 'second', 'second'), (700004, 'ja', 'first', 'first')");
            database.setVersion(25);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases WHERE aliasOrder = -1"));
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            byte[] replacement = ("{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":2,"
                + "\"entries\":[{\"id\":700004,\"name\":\"name\",\"aliases\":[\"second\",\"first\"]}]}")
                .getBytes(StandardCharsets.UTF_8);
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(replacement), context.getCacheDir(), () -> false));
            assertEquals("second", scalarString(database, "SELECT alias FROM TagTranslationAliases WHERE aliasOrder = 0"));
            assertEquals("first", scalarString(database, "SELECT alias FROM TagTranslationAliases WHERE aliasOrder = 1"));
        }
    }

    @Test
    public void failedAliasOrderUpgradeRollsBackEarlierMigrationChanges() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " VALUES (1, 'ja', 'alias', 'alias')");
            database.execSQL("DROP TABLE TagTranslationGenerations");
            // A conflicting column forces ALTER TABLE to fail after the generation table is created.
            database.setVersion(24);
        }
        assertThrows(SQLiteException.class, () -> {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                helper.getWritableDatabase();
            }
        });
        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(24, database.getVersion());
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='table' AND name='TagTranslationGenerations'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases WHERE alias = 'alias'"));
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            database.execSQL("ALTER TABLE TagTranslationAliases RENAME TO conflicting_aliases");
            database.execSQL("DROP INDEX TagTranslationAliases_locale_key_idx");
            database.execSQL("CREATE TABLE TagTranslationAliases (idTag INTEGER NOT NULL, locale TEXT NOT NULL,"
                + " alias TEXT NOT NULL, aliasKey TEXT NOT NULL, PRIMARY KEY (idTag, locale, aliasKey))");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey)"
                + " SELECT idTag, locale, alias, aliasKey FROM conflicting_aliases");
            database.execSQL("DROP TABLE conflicting_aliases");
            database.execSQL("CREATE INDEX TagTranslationAliases_locale_key_idx"
                + " ON TagTranslationAliases (locale, aliasKey, idTag)");
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(-1, scalar(database, "SELECT aliasOrder FROM TagTranslationAliases WHERE alias = 'alias'"));
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        }
    }

    @Test
    public void version26UpgradePreservesAliasesWhenSearchKeysLaterCollide() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion26AliasTable(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 4)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (1, 'ja', 'name', 'name')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                + " VALUES (1, 'ja', 'first', 'first', 0), (1, 'ja', 'second', 'second', 1),"
                + " (2, 'ja', 'legacy', 'legacy', -1)");
            database.setVersion(26);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(4, TagTranslationCatalog.installedRevision(database, "ja"));
            database.execSQL("UPDATE TagTranslationAliases SET aliasKey = 'same' WHERE idTag = 1");
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases"
                + " WHERE idTag = 1 AND locale = 'ja' AND aliasKey = 'same'"));
            assertEquals("first", scalarString(database,
                "SELECT alias FROM TagTranslationAliases WHERE idTag = 1 AND aliasOrder = 0"));
            assertEquals("second", scalarString(database,
                "SELECT alias FROM TagTranslationAliases WHERE idTag = 1 AND aliasOrder = 1"));
            assertEquals(-1, scalar(database, "SELECT aliasOrder FROM TagTranslationAliases WHERE alias = 'legacy'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='TagTranslationAliases_locale_key_idx'"));
            assertTrue(planUsesAliasTagIndex(database, "EXPLAIN QUERY PLAN SELECT alias FROM TagTranslationAliases"
                + " WHERE idTag = 1 AND locale = 'ja' ORDER BY aliasOrder"));
            assertTrue(planUsesAliasTagIndex(database, "EXPLAIN QUERY PLAN SELECT t.idTag, a.alias"
                + " FROM TagTranslations t LEFT JOIN TagTranslationAliases a"
                + " ON a.idTag = t.idTag AND a.locale = t.locale WHERE t.locale = 'ja'"));
        }
    }

    @Test
    public void failedAliasIdentityUpgradeRetainsOldTableAndCanBeRetried() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            useVersion26AliasTable(database);
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                + " VALUES (1, 'ja', 'first', 'first', 0)");
            // This name conflict fails when the new index is created, after copying and dropping the old table.
            database.execSQL("CREATE TABLE TagTranslationAliases_tag_order_idx (placeholder INTEGER)");
            database.setVersion(26);
        }
        assertThrows(SQLiteException.class, () -> {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                helper.getWritableDatabase();
            }
        });
        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(26, database.getVersion());
            assertEquals("first", scalarString(database, "SELECT alias FROM TagTranslationAliases"));
            assertEquals(0, scalar(database, "SELECT aliasOrder FROM TagTranslationAliases"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='TagTranslationAliases_locale_key_idx'"));
            database.execSQL("DROP TABLE TagTranslationAliases_tag_order_idx");
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals("first", scalarString(database, "SELECT alias FROM TagTranslationAliases"));
            assertEquals(0, scalar(database, "SELECT aliasOrder FROM TagTranslationAliases"));
        }
    }

    @Test
    public void version27UnicodeRebuildRollsBackAndPreservesCollidingAliasesOnRetry() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700004, 'canonical', 3, 1)");
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (700004, 'rule', 3, 1)");
            database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (700004)");
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 9)");
            database.execSQL("INSERT INTO TagTranslationGenerations (locale, generation) VALUES ('ja', 4)");
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (700004, 'ja', '\u1c89', 'old-display')");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                + " VALUES (700004, 'ja', '\u1c89', 'old-first', 0),"
                + " (700004, 'ja', '\u1c8a', 'old-second', 1)");
            database.execSQL("CREATE TRIGGER fail_unicode_rebuild BEFORE UPDATE OF aliasKey ON TagTranslationAliases"
                + " WHEN OLD.aliasOrder = 1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
            database.setVersion(27);
        }
        assertThrows(SQLiteException.class, () -> {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                helper.getWritableDatabase();
            }
        });
        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(27, database.getVersion());
            assertEquals("old-display", scalarString(database, "SELECT displayKey FROM TagTranslations"));
            assertEquals("old-first", scalarString(database, "SELECT aliasKey FROM TagTranslationAliases WHERE aliasOrder = 0"));
            assertEquals("old-second", scalarString(database, "SELECT aliasKey FROM TagTranslationAliases WHERE aliasOrder = 1"));
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                + " AND name IN ('TagTranslations_locale_key_idx', 'TagTranslationAliases_locale_key_idx')"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagRules WHERE idTag = 700004 AND status = 1"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM AccountTagBlacklist WHERE idTag = 700004"));
            database.execSQL("DROP TRIGGER fail_unicode_rebuild");
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals("\u1c89", scalarString(database, "SELECT displayName FROM TagTranslations"));
            assertEquals("\u1c8a", scalarString(database, "SELECT displayKey FROM TagTranslations"));
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM TagTranslationAliases WHERE aliasKey = '\u1c8a'"));
            assertEquals(2, scalar(database, "SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                + " AND name IN ('TagTranslations_locale_key_idx', 'TagTranslationAliases_locale_key_idx')"));
            assertEquals("\u1c89", scalarString(database, "SELECT alias FROM TagTranslationAliases WHERE aliasOrder = 0"));
            assertEquals("\u1c8a", scalarString(database, "SELECT alias FROM TagTranslationAliases WHERE aliasOrder = 1"));
            assertEquals(9, TagTranslationCatalog.installedRevision(database, "ja"));
            assertEquals(4, TagTranslationCatalog.generationSnapshot(database));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagRules WHERE idTag = 700004 AND status = 1"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM AccountTagBlacklist WHERE idTag = 700004"));
        }
    }

    @Test
    public void version27UnicodeRebuildHandlesMultipleBatchesAndUnchangedRows() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.beginTransaction();
            try {
                for (int id = 1; id <= 403; id++) {
                    boolean unchanged = id % 2 == 0;
                    database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                            + " VALUES (?, 'ja', ?, ?)",
                        new Object[]{id, unchanged ? "unchanged" : "\u1c89 name " + id,
                            unchanged ? "unchanged" : "old"});
                }
                database.execSQL("CREATE TRIGGER protect_unchanged BEFORE UPDATE ON TagTranslations"
                    + " WHEN OLD.displayKey = 'unchanged' BEGIN SELECT RAISE(ABORT, 'unchanged row updated'); END");
                database.execSQL("CREATE TRIGGER fail_later_batch BEFORE UPDATE ON TagTranslations"
                    + " WHEN OLD.idTag = 301 BEGIN SELECT RAISE(ABORT, 'later batch failed'); END");
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            database.setVersion(27);
        }
        assertThrows(SQLiteException.class, () -> {
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                helper.getWritableDatabase();
            }
        });
        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(27, database.getVersion());
            assertEquals(202, scalar(database, "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'old'"));
            database.execSQL("DROP TRIGGER fail_later_batch");
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(202, scalar(database, "SELECT COUNT(*) FROM TagTranslations"
                + " WHERE displayKey = '\u1c8a name ' || idTag AND displayName = '\u1c89 name ' || idTag"));
            assertEquals(201, scalar(database, "SELECT COUNT(*) FROM TagTranslations"
                + " WHERE displayKey = 'unchanged' AND displayName = 'unchanged'"));
        }
    }

    private static void useVersion26AliasTable(SQLiteDatabase database) {
        database.execSQL("DROP TABLE TagTranslationAliases");
        database.execSQL("CREATE TABLE TagTranslationAliases (idTag INTEGER NOT NULL, locale TEXT NOT NULL,"
            + " alias TEXT NOT NULL, aliasKey TEXT NOT NULL, aliasOrder INTEGER NOT NULL DEFAULT -1"
            + " CHECK (aliasOrder >= -1), PRIMARY KEY (idTag, locale, aliasKey))");
        database.execSQL("CREATE INDEX TagTranslationAliases_locale_key_idx"
            + " ON TagTranslationAliases (locale, aliasKey, idTag)");
    }

    private static void useVersion25AliasTable(SQLiteDatabase database) {
        database.execSQL("DROP TABLE TagTranslationAliases");
        database.execSQL("CREATE TABLE TagTranslationAliases (idTag INTEGER NOT NULL, locale TEXT NOT NULL,"
            + " alias TEXT NOT NULL, aliasKey TEXT NOT NULL, PRIMARY KEY (idTag, locale, aliasKey))");
        database.execSQL("CREATE INDEX TagTranslationAliases_locale_key_idx"
            + " ON TagTranslationAliases (locale, aliasKey, idTag)");
    }

    @Test
    public void version21UpgradeCreatesTagSuggestionIndex() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP INDEX Tags_type_name_suggestion_idx");
            database.setVersion(21);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM sqlite_master"
                + " WHERE type='index' AND name='Tags_type_name_suggestion_idx'"));
        }
    }

    @Test
    public void version20UpgradeSeparatesRulesAndDiscardsAccountFlags() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagRules");
            database.execSQL("DROP TABLE AccountTagBlacklist");
            database.execSQL("ALTER TABLE Tags ADD COLUMN status INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE Tags ADD COLUMN online INTEGER NOT NULL DEFAULT 0");
            database.execSQL("INSERT INTO Tags (idTag, name, type, count, status, online)"
                + " VALUES (132, 'saved', 3, 50, 2, 1), (133, 'blocked', 3, 40, 0, 1)");
            database.execSQL("INSERT INTO Gallery VALUES (100, 'fixture', '', '', 0, 1, '', 0, 0, 0, 0, 0)");
            Queries.FavoriteTable.insert(100);
            database.execSQL("INSERT INTO GalleryTags (id_gallery, id_tag) VALUES (100, 132)");
            database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('bundled', 43)");
            database.setVersion(20);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(43, TagCatalog.installedSeedRevision(database));
            assertEquals(TagStatus.ACCEPTED, Queries.TagTable.getTagById(132).getStatus());
            assertEquals(TagStatus.DEFAULT, Queries.TagTable.getTagById(133).getStatus());
            assertEquals(1, Queries.GalleryBridgeTable.getTagsForGallery(100).getCount(TagType.TAG));
            try (Cursor rows = database.query("AccountTagBlacklist", null, null, null, null, null, null);
                 Cursor columns = database.query("Tags", null, "idTag = 132", null, null, null, null)) {
                assertEquals(0, rows.getCount());
                assertEquals(-1, columns.getColumnIndex("online"));
                assertEquals(-1, columns.getColumnIndex("status"));
            }
        }
    }
    @Test
    public void version19UpgradeKeepsUpstreamRevisionSeparateFromTheBundledRevision() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("DROP TABLE TagCatalogState");
            database.execSQL("CREATE TABLE TagCatalogState (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)");
            database.execSQL("INSERT INTO TagCatalogState (id, revision) VALUES (1, 42)");
            database.setVersion(19);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(42, TagCatalog.installedRevision(database));
            assertEquals(0, TagCatalog.installedSeedRevision(database));
        }
    }

    @Test
    public void upgradingAfterDowngradeKeepsBothCatalogRevisions() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('legacy-upstream', 42), ('bundled', 43)");
            database.setVersion(19);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(42, TagCatalog.installedRevision(database));
            assertEquals(43, TagCatalog.installedSeedRevision(database));
        }
    }

    @Test
    public void version16UpgradeKeepsSearchBookmarks() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO Bookmark VALUES ('https://example.org/?q=test',2,0,0)");
            database.execSQL("DROP TABLE SearchPlans");
            database.setVersion(16);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM Bookmark WHERE page=2"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM SearchPlans"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
        }
    }

    @Test
    public void version13UpgradePreservesExistingRowsAndManualBookmark() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            legacy.execSQL(Queries.GalleryTable.CREATE_TABLE);
            legacy.execSQL(Queries.TagTable.CREATE_TABLE);
            legacy.execSQL(Queries.GalleryBridgeTable.CREATE_TABLE);
            legacy.execSQL(Queries.BookmarkTable.CREATE_TABLE);
            legacy.execSQL(Queries.DownloadTable.CREATE_TABLE);
            legacy.execSQL(Queries.HistoryTable.CREATE_TABLE);
            legacy.execSQL(Queries.FavoriteTable.CREATE_TABLE);
            legacy.execSQL(Queries.ResumeTable.CREATE_TABLE);
            legacy.execSQL(Queries.StatusTable.CREATE_TABLE);
            legacy.execSQL(Queries.StatusMangaTable.CREATE_TABLE);
            legacy.execSQL("INSERT INTO Gallery VALUES (123,'English','Japanese','Pretty',5,456,'jj',1234,100,100,50,50)");
            legacy.execSQL("INSERT INTO Favorite VALUES (123,99)");
            legacy.execSQL("INSERT INTO Resume VALUES (123,7)");
            legacy.execSQL("INSERT INTO Status VALUES ('Custom',12345)");
            legacy.execSQL("INSERT INTO StatusManga VALUES (123,'Custom',88)");
            legacy.execSQL("INSERT INTO Downloads VALUES (123,0,3)");
            legacy.execSQL("INSERT INTO History VALUES (123,456,'Title',0,77)");
            legacy.execSQL("INSERT INTO Bookmark VALUES ('https://example.org/',2,0,0)");
            legacy.setVersion(13);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(7, scalar(database, "SELECT page FROM Resume WHERE id_gallery=123"));
            assertEquals(99, scalar(database, "SELECT time FROM Favorite WHERE id_gallery=123"));
            assertEquals(12345, scalar(database, "SELECT color FROM Status WHERE name='Custom'"));
            assertEquals(88, scalar(database, "SELECT time FROM StatusManga WHERE gallery=123"));
            assertEquals(3, scalar(database, "SELECT range_end FROM Downloads WHERE id_gallery=123"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(0, scalar(database, "SELECT range_start FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(3, scalar(database, "SELECT range_end FROM DownloadTasks WHERE id_gallery=123"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM DownloadTasks WHERE state='PAUSED'"));
            assertEquals(77, scalar(database, "SELECT time FROM History WHERE id=123"));
            assertEquals(2, scalar(database, "SELECT page FROM Bookmark"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM SearchPlans"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
        }
    }

    @Test
    public void version17UpgradePreservesReadingProgressAndCreatesReaderBookmarks() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO ReadingProgress VALUES (123,7,0.5,456)");
            database.execSQL("DROP TABLE ReaderBookmarks");
            database.setVersion(17);
        }
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertEquals(28, database.getVersion());
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM ReadingProgress " +
                "WHERE id_gallery=123 AND page=7 AND offset=0.5 AND updated_at=456"));
            database.execSQL("INSERT INTO ReaderBookmarks (id_gallery,page,title,created_at,updated_at) " +
                "VALUES (123,7,'Page 7',456,456)");
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks WHERE id_gallery=123 AND page=7"));
        }
    }

    @Test
    public void version12CreatesStatusesBeforeInitializingThem() {
        Context context = RuntimeEnvironment.getApplication();
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            new DatabaseHelper(context).onUpgrade(database, 12, 16);
            assertEquals(6, scalar(database, "SELECT COUNT(*) FROM Status"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
        }
    }

    @Test
    public void unsupportedDowngradeFailsWithoutChangingTagDataOrSchemaVersion() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        int newerVersion;
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical', 3, 5)");
            database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'canonical', 3, 1)");
            newerVersion = database.getVersion() + 1;
            database.setVersion(newerVersion);
        }

        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteException failure = assertThrows(SQLiteException.class, helper::getWritableDatabase);
            assertTrue(failure.getMessage().contains("Database downgrade"));
        }

        try (SQLiteDatabase database = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
            assertEquals(newerVersion, database.getVersion());
            assertEquals("canonical", scalarString(database, "SELECT name FROM Tags WHERE idTag = 1"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM TagRules WHERE idTag = 1 AND status = 1"));
        }
    }

    private int scalar(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private String scalarString(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }

    private boolean planUsesAliasTagIndex(SQLiteDatabase database, String sql) {
        try (Cursor cursor = database.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                if (cursor.getString(3).contains("TagTranslationAliases_tag_order_idx")) return true;
            }
            return false;
        }
    }

    @Test
    public void olderUpgradePathsDoNotRecreateExistingColumnsOrQueryFutureTables() {
        Context context = RuntimeEnvironment.getApplication();
        for (int version : new int[]{6, 8}) {
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
            try (SQLiteDatabase legacy = context.openOrCreateDatabase(DatabaseHelper.DATABASE_NAME, 0, null)) {
                legacy.execSQL(Queries.GalleryTable.CREATE_TABLE);
                legacy.execSQL("ALTER TABLE Gallery ADD COLUMN favorite INT NOT NULL DEFAULT 0");
                legacy.execSQL(Queries.TagTable.CREATE_TABLE);
                legacy.execSQL(Queries.GalleryBridgeTable.CREATE_TABLE);
                legacy.execSQL(Queries.BookmarkTable.CREATE_TABLE);
                if (version == 8) {
                    legacy.execSQL("CREATE TABLE Downloads (id_gallery INT NOT NULL PRIMARY KEY)");
                    legacy.execSQL(Queries.HistoryTable.CREATE_TABLE);
                }
                legacy.setVersion(version);
            }
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                assertEquals(28, database.getVersion());
                assertEquals(0, scalar(database, "SELECT COUNT(time) FROM Favorite"));
                assertEquals(0, scalar(database, "SELECT COUNT(range_start) FROM Downloads"));
                assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReadingProgress"));
                assertEquals(0, scalar(database, "SELECT COUNT(*) FROM ReaderBookmarks"));
            }
        }
    }
}
