package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class TagImportContentionTest {
    private static final String TAG = "TagImportContention";
    private static final int TAG_ID = 500_001;
    private static final long TIMEOUT_SECONDS = 20;

    @Test
    public void reportsReadAndRuleWriteWhileCatalogCommitIsPending() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Use a fresh benchmark installation", context.getDatabasePath(DatabaseHelper.DATABASE_NAME).exists());
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            String journalMode = DatabaseUtils.stringForQuery(database, "PRAGMA journal_mode", null);
            assertTrue(TagCatalog.apply(database, snapshot("before"), 1, 7, () -> false));
            Tag accepted = new Tag("before", 50, TAG_ID, TagType.PARODY, TagStatus.ACCEPTED);
            CountDownLatch beforeCommit = new CountDownLatch(1);
            CountDownLatch commitAllowed = new CountDownLatch(1);
            CountDownLatch accessesStarted = new CountDownLatch(2);
            ExecutorService threads = Executors.newFixedThreadPool(3);
            try {
                Future<Boolean> update = threads.submit(() -> TagCatalog.apply(database, snapshot("after"), 2, 7, () -> {
                    if (TagCatalog.installedRevision(database) == 2) {
                        assertTrue(database.inTransaction());
                        beforeCommit.countDown();
                        await(commitAllowed);
                    }
                    return false;
                }));
                await(beforeCommit);

                long accessStart = SystemClock.elapsedRealtimeNanos();
                Future<Tag> read = threads.submit(() -> {
                    accessesStarted.countDown();
                    return Queries.TagTable.getTagById(TAG_ID);
                });
                Future<Integer> write = threads.submit(() -> {
                    accessesStarted.countDown();
                    return Queries.TagTable.updateTag(accepted);
                });
                await(accessesStarted);
                Tag concurrentTag = beforeCommit(read);
                Integer concurrentWrite = beforeCommit(write);
                if (concurrentTag != null) assertEquals("before", concurrentTag.getName());

                long holdMs = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - accessStart);
                commitAllowed.countDown();
                assertTrue(update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                read.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(1, write.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
                Tag result = Queries.TagTable.getTagById(TAG_ID);
                assertEquals("after", result.getName());
                assertEquals(TagStatus.ACCEPTED, result.getStatus());
                Log.i(TAG, "journalMode=" + journalMode + " heldMs=" + holdMs
                    + " readBeforeCommit=" + (concurrentTag != null) + " ruleWriteBeforeCommit=" + (concurrentWrite != null));
            } finally {
                commitAllowed.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            Queries.setDb(null);
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        }
    }

    @Test
    public void queuedRuleEditLeavesMainThreadFreeWhileCatalogCommitIsPending() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Use a fresh benchmark installation", context.getDatabasePath(DatabaseHelper.DATABASE_NAME).exists());
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            assertTrue(TagCatalog.apply(database, snapshot("before"), 1, 7, () -> false));
            CountDownLatch beforeCommit = new CountDownLatch(1);
            CountDownLatch commitAllowed = new CountDownLatch(1);
            CountDownLatch mainReturned = new CountDownLatch(1);
            CountDownLatch editFinished = new CountDownLatch(1);
            AtomicReference<TagRuleEdits.Result> result = new AtomicReference<>();
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> update = threads.submit(() -> TagCatalog.apply(database, snapshot("after"), 2, 7, () -> {
                    if (TagCatalog.installedRevision(database) == 2) {
                        beforeCommit.countDown();
                        await(commitAllowed);
                    }
                    return false;
                }));
                await(beforeCommit);
                Future<?> submit = threads.submit(() -> InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    TagRuleEdits.cycle(new Tag("before", 50, TAG_ID, TagType.PARODY, TagStatus.DEFAULT), true, edit -> {
                        if (Looper.myLooper() == Looper.getMainLooper()) result.set(edit);
                        editFinished.countDown();
                    });
                    mainReturned.countDown();
                }));
                assertTrue("The UI thread waited for SQLite", mainReturned.await(5, TimeUnit.SECONDS));
                assertFalse("An edit completed before the catalog released its writer",
                    editFinished.await(250, TimeUnit.MILLISECONDS));

                commitAllowed.countDown();
                assertTrue(update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                submit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                await(editFinished);
                assertEquals(TagRuleEdits.Outcome.SAVED, result.get().outcome);
                assertEquals(TagStatus.ACCEPTED, result.get().status);
                Tag saved = Queries.TagTable.getTagById(TAG_ID);
                assertEquals("after", saved.getName());
                assertEquals(TagStatus.ACCEPTED, saved.getStatus());
            } finally {
                commitAllowed.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            Queries.setDb(null);
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Timed out waiting for the SQLite test workers", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static <Result> Result beforeCommit(Future<Result> access) throws Exception {
        try {
            return access.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException waiting) {
            return null;
        }
    }

    private static ByteArrayInputStream snapshot(String name) {
        return new ByteArrayInputStream(("[[" + TAG_ID + ",\"" + name + "\",50,1],"
            + "[2,\"character\",10,2],[3,\"tag\",10,3],[4,\"artist\",10,4],"
            + "[5,\"group\",10,5],[6,\"language\",10,6],[7,\"category\",10,7]]")
            .getBytes(StandardCharsets.UTF_8));
    }
}
