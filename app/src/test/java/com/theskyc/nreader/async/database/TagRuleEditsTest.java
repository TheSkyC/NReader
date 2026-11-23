package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagRuleEditsTest {
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final Queue<Runnable> delivery = new ArrayDeque<>();
    private final List<TagRuleEdits.Result> results = new ArrayList<>();
    private final TagRuleEdits edits = new TagRuleEdits(worker::add, delivery::add);
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        Queries.setDb(database);
    }

    @After public void closeDatabase() {
        Queries.setDb(null);
        database.close();
    }

    @Test public void tapsAndClearUseSavedStateInSubmissionOrder() {
        Tag stale = tag(42, TagStatus.AVOIDED);
        Queries.TagTable.insert(tag(42, TagStatus.DEFAULT));
        database.execSQL("UPDATE Tags SET name = 'renamed' WHERE idTag = 42");
        database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (42)");
        edits.enqueueCycle(stale, true, results::add);
        edits.enqueueCycle(stale, true, results::add);
        edits.enqueueClear(results::add);
        edits.enqueueCycle(stale, true, results::add);

        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(stale));
        drain(worker);
        assertTrue(results.isEmpty());
        drain(delivery);
        assertEquals(4, results.size());
        assertEquals(TagStatus.ACCEPTED, results.get(0).status);
        assertEquals(TagStatus.AVOIDED, results.get(1).status);
        assertEquals(TagStatus.DEFAULT, results.get(2).status);
        assertEquals(TagStatus.ACCEPTED, results.get(3).status);
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(stale));
        assertEquals("renamed", DatabaseUtils.stringForQuery(database, "SELECT name FROM TagRules WHERE idTag = 42", null));
        assertEquals(1, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM AccountTagBlacklist", null));
        assertEquals(TagStatus.AVOIDED, stale.getStatus());
    }

    @Test public void fullListRejectsNewRuleButCanEditExistingRule() {
        for (int id = 1; id <= TagV2.MAXTAGS; id++) {
            assertEquals(1, Queries.TagTable.updateTag(tag(id, TagStatus.ACCEPTED)));
        }
        edits.enqueueCycle(tag(1000, TagStatus.DEFAULT), true, results::add);
        edits.enqueueCycle(tag(42, TagStatus.DEFAULT), true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.LIMIT_REACHED, results.get(0).outcome);
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(tag(1000, TagStatus.DEFAULT)));
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.storedRuleStatus(tag(42, TagStatus.DEFAULT)));
    }

    @Test public void incompatibleSavedRuleCanStillBeRemovedFromAppliedFilters() {
        Tag rule = tag(42, TagStatus.AVOIDED);
        assertEquals(1, Queries.TagTable.updateTag(rule));
        Queries.TagTable.insert(new Tag("other-type", 50, 42, TagType.ARTIST, TagStatus.DEFAULT));

        edits.enqueueCycle(rule, true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagStatus.DEFAULT, results.get(0).status);
        assertEquals(0, DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagRules", null));
        assertEquals(TagType.ARTIST, Queries.TagTable.getTagById(42).getType());
    }

    @Test public void obsoleteQueuedTypeCannotReplaceCurrentRule() {
        Tag obsolete = tag(42, TagStatus.DEFAULT);
        edits.enqueueCycle(obsolete, true, results::add);
        Tag resolved = new Tag("resolved", 50, 42, TagType.ARTIST, TagStatus.AVOIDED);
        Queries.TagTable.insert(resolved);
        assertEquals(1, Queries.TagTable.updateTag(resolved));

        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.FAILED, results.get(0).outcome);
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.storedRuleStatus(resolved));
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(obsolete));
    }

    @Test public void restoreStartedAfterCommitInvalidatesDeliveryWithoutUndoingTheCommit() {
        Tag rule = tag(42, TagStatus.DEFAULT);
        long revision = TagRuleEdits.revision();
        edits.enqueueCycle(rule, true, results::add);
        drain(worker);
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(rule));
        assertTrue(TagRuleEdits.revision() > revision);

        Login.beginOnlineTagsRestore();
        Login.endOnlineTagsRestore();
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.SUPERSEDED, results.get(0).outcome);
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(rule));
    }

    @Test public void restoreInvalidatesQueuedCycleAndClear() {
        Tag restored = tag(42, TagStatus.AVOIDED);
        edits.enqueueCycle(restored, true, results::add);
        edits.enqueueClear(results::add);
        Login.beginOnlineTagsRestore();
        try {
            assertEquals(1, Queries.TagTable.updateTag(restored));
            edits.enqueueClear(results::add);
        } finally {
            Login.endOnlineTagsRestore();
        }
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.SUPERSEDED, results.get(0).outcome);
        assertEquals(TagRuleEdits.Outcome.SUPERSEDED, results.get(1).outcome);
        assertEquals(TagRuleEdits.Outcome.SUPERSEDED, results.get(2).outcome);
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.storedRuleStatus(restored));
    }

    @Test public void rejectedWriteReportsFailureKeepsRuleAndDoesNotStopQueue() {
        Tag accepted = tag(42, TagStatus.ACCEPTED);
        assertEquals(1, Queries.TagTable.updateTag(accepted));
        database.execSQL("CREATE TRIGGER fail_rule BEFORE INSERT ON TagRules"
            + " BEGIN SELECT RAISE(ABORT, 'injected rule failure'); END");
        edits.enqueueCycle(accepted, true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.FAILED, results.get(0).outcome);
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(accepted));

        database.execSQL("DROP TRIGGER fail_rule");
        edits.enqueueCycle(accepted, true, results::add);
        drain(worker);
        drain(delivery);
        assertEquals(TagRuleEdits.Outcome.SAVED, results.get(1).outcome);
        assertEquals(TagStatus.AVOIDED, Queries.TagTable.storedRuleStatus(accepted));
    }

    @Test public void bulkSetChangesAllCandidatesInOneOperation() {
        Tag first = tag(42, TagStatus.DEFAULT);
        Tag second = tag(43, TagStatus.DEFAULT);
        Queries.TagTable.insert(first);
        Queries.TagTable.insert(second);
        List<Tag> candidates = new ArrayList<>();
        candidates.add(first);
        candidates.add(second);

        edits.enqueueBulkSet(candidates, TagStatus.ACCEPTED, true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.SAVED, results.get(0).outcome);
        assertEquals(2, results.get(0).affected);
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(first));
        assertEquals(TagStatus.ACCEPTED, Queries.TagTable.storedRuleStatus(second));
    }

    @Test public void bulkSetRejectsLimitWithoutPartialWrites() {
        for (int id = 1; id <= TagV2.MAXTAGS; id++) {
            assertEquals(1, Queries.TagTable.updateTag(tag(id, TagStatus.ACCEPTED)));
        }
        Tag candidate = tag(1000, TagStatus.DEFAULT);
        Queries.TagTable.insert(candidate);
        List<Tag> candidates = new ArrayList<>();
        candidates.add(candidate);

        edits.enqueueBulkSet(candidates, TagStatus.ACCEPTED, true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.LIMIT_REACHED, results.get(0).outcome);
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(candidate));
    }

    @Test public void bulkSetRollsBackWhenOneRuleWriteFails() {
        Tag first = tag(42, TagStatus.DEFAULT);
        Tag second = tag(43, TagStatus.DEFAULT);
        Queries.TagTable.insert(first);
        Queries.TagTable.insert(second);
        database.execSQL("CREATE TRIGGER fail_bulk_rule BEFORE INSERT ON TagRules"
            + " BEGIN SELECT RAISE(ABORT, 'injected bulk failure'); END");
        List<Tag> candidates = new ArrayList<>();
        candidates.add(first);
        candidates.add(second);

        edits.enqueueBulkSet(candidates, TagStatus.ACCEPTED, true, results::add);
        drain(worker);
        drain(delivery);

        assertEquals(TagRuleEdits.Outcome.FAILED, results.get(0).outcome);
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(first));
        assertEquals(TagStatus.DEFAULT, Queries.TagTable.storedRuleStatus(second));
    }

    private static Tag tag(int id, TagStatus status) {
        return new Tag("tag-" + id, 50, id, TagType.TAG, status);
    }

    private static void drain(Queue<Runnable> queue) {
        while (!queue.isEmpty()) queue.remove().run();
    }
}
