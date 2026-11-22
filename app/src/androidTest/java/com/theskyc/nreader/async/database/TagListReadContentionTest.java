package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.TagV2;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(AndroidJUnit4.class)
public class TagListReadContentionTest {
    private static final String LOG = "TagListReadContention";
    private static final int ROWS = 36_000;
    private static final int FIRST_ID = 500_001;
    private static final String OLD_NAME = "before";
    private static final String NEW_NAME = "after";
    private static final int TIMEOUT_SECONDS = 15;

    @Test
    public void compareDefaultAndWalForFirstListReadAndCursorRefill() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Use a fresh benchmark installation", context.getDatabasePath(DatabaseHelper.DATABASE_NAME).exists());
        run(context, false);
        run(context, true);
    }

    private static void run(Context context, boolean wal) throws Exception {
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            if (wal) helper.setWriteAheadLoggingEnabled(true);
            SQLiteDatabase db = helper.getWritableDatabase();
            TagV2.initMinCount(context);
            seed(db);
            String journal = DatabaseUtils.stringForQuery(db, "PRAGMA journal_mode", null);
            long sync = DatabaseUtils.longForQuery(db, "PRAGMA synchronous", null);
            if (wal) assertEquals("wal", journal);
            ExecutorService threads = Executors.newFixedThreadPool(3);
            CountDownLatch beforeCommit = new CountDownLatch(1);
            CountDownLatch commitAllowed = new CountDownLatch(1);
            try (SQLiteCursor loaded = openCursor()) {
                long loadStart = SystemClock.elapsedRealtime();
                assertEquals(ROWS, loaded.getCount());
                assertTrue(loaded.moveToFirst());
                long firstWindowMs = SystemClock.elapsedRealtime() - loadStart;
                int windowRows = loaded.getWindow().getNumRows();
                assertTrue("Fixture must exceed the first native cursor window", windowRows < ROWS);

                Future<Boolean> update = threads.submit(() -> TagCatalog.apply(db, snapshot(), 1, 7, () -> {
                    if (TagCatalog.installedRevision(db) == 1) {
                        beforeCommit.countDown();
                        await(commitAllowed);
                    }
                    return false;
                }));
                await(beforeCommit);
                CountDownLatch readersStarted = new CountDownLatch(2);
                AtomicLong firstReadMs = new AtomicLong();
                AtomicLong refillMs = new AtomicLong();
                Future<String> firstRead = threads.submit(() -> {
                    long start = SystemClock.elapsedRealtime();
                    readersStarted.countDown();
                    String result = firstName();
                    firstReadMs.set(SystemClock.elapsedRealtime() - start);
                    return result;
                });
                Future<Integer> refill = threads.submit(() -> {
                    long start = SystemClock.elapsedRealtime();
                    readersStarted.countDown();
                    assertTrue(loaded.moveToLast());
                    int result = loaded.getInt(loaded.getColumnIndexOrThrow("idTag"));
                    refillMs.set(SystemClock.elapsedRealtime() - start);
                    return result;
                });
                await(readersStarted);
                long deadline = SystemClock.elapsedRealtime() + 1_500;
                String firstBeforeCommit;
                Integer refillBeforeCommit;
                try {
                    firstBeforeCommit = beforeDeadline(firstRead, deadline);
                    refillBeforeCommit = beforeDeadline(refill, deadline);
                } finally {
                    commitAllowed.countDown();
                }
                assertTrue(update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                String first = firstRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                int lastId = refill.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertTrue(OLD_NAME.equals(first) || NEW_NAME.equals(first));
                assertEquals(FIRST_ID + ROWS - 1, lastId);
                assertEquals(NEW_NAME, firstName());
                if (firstBeforeCommit != null) assertEquals(OLD_NAME, firstBeforeCommit);
                Log.i(LOG, "journal=" + journal + " synchronous=" + sync + " rows=" + ROWS
                    + " firstWindowRows=" + windowRows + " firstWindowMs=" + firstWindowMs
                    + " firstReadBeforeCommit=" + (firstBeforeCommit != null)
                    + " firstReadMs=" + firstReadMs.get()
                    + " refillBeforeCommit=" + (refillBeforeCommit != null) + " refillMs=" + refillMs.get());
            } finally {
                commitAllowed.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            assertTrue(context.deleteDatabase(DatabaseHelper.DATABASE_NAME));
        }
    }

    private static SQLiteCursor openCursor() {
        return (SQLiteCursor) Queries.TagTable.getFilterCursor("", TagType.TAG, false, false);
    }

    private static String firstName() {
        try (SQLiteCursor cursor = openCursor()) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(cursor.getColumnIndexOrThrow("name"));
        }
    }

    private static void seed(SQLiteDatabase db) {
        char[] namePadding = new char[160];
        Arrays.fill(namePadding, 'x');
        String prefix = new String(namePadding);
        db.execSQL("WITH RECURSIVE rows(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM rows WHERE i + 1 < ?)"
            + " INSERT INTO Tags(idTag, name, count, type)"
            + " SELECT ? + i, CASE i WHEN 0 THEN ? ELSE ? || (? + i) END,"
            + " CASE i WHEN 0 THEN 100 ELSE 50 END, ? FROM rows",
            new Object[]{ROWS, FIRST_ID, OLD_NAME, prefix, FIRST_ID, TagType.TAG.getId()});
    }

    private static ByteArrayInputStream snapshot() {
        return new ByteArrayInputStream(("[[1,\"parody\",10,1],[2,\"character\",10,2],"
            + "[" + FIRST_ID + ",\"" + NEW_NAME + "\",100,3],[4,\"artist\",10,4],"
            + "[5,\"group\",10,5],[6,\"language\",10,6],[7,\"category\",10,7]]")
            .getBytes(StandardCharsets.UTF_8));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Timed out waiting for tag list readers", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static <T> T beforeDeadline(Future<T> result, long deadline) throws Exception {
        try {
            return result.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
            return null;
        }
    }
}
