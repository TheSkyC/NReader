package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.TestDriver;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.theskyc.nreader.WorkManagerTestScope;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationRebuildSchedulingTest {
    @Rule public final WorkManagerTestScope workManagerScope = new WorkManagerTestScope();

    @Test public void uniqueWorkRetriesFromCheckpointAfterFailure() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        context.deleteDatabase("Entries.db");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            new Configuration.Builder().setExecutor(executor).setClock(clock::get).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        WorkManager manager = WorkManager.getInstance(context);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            ReflectionHelpers.setStaticField(Database.class, "database", database);
            for (int id = 1; id <= 101; id++) {
                database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                    + " VALUES (?, 'ja', 'name', 'old')", new Object[]{id});
            }
            database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage) VALUES (1, 0)");
            database.execSQL("CREATE TRIGGER fail_rebuild BEFORE UPDATE ON TagTranslations"
                + " WHEN OLD.idTag = 101 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            TagTranslationKeyRebuildWorker.schedule(context);
            WorkInfo retry = awaitState(manager, WorkInfo.State.ENQUEUED, 1);
            assertEquals(100, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'name'", null));
            TagTranslationKeyRebuildWorker.schedule(context);
            List<WorkInfo> unique = manager.getWorkInfosForUniqueWork("tag-translation-key-rebuild").get(10, TimeUnit.SECONDS);
            assertEquals(1, unique.size());
            assertEquals(retry.getId(), unique.get(0).getId());
            database.execSQL("DROP TRIGGER fail_rebuild");
            clock.addAndGet(TimeUnit.MINUTES.toMillis(1));
            TestDriver driver = WorkManagerTestInitHelper.getTestDriver(context);
            driver.setInitialDelayMet(retry.getId());
            awaitState(manager, WorkInfo.State.SUCCEEDED, 0);
            assertFalse(TagTranslationCatalog.isKeyRebuildPending(database));
            assertEquals(101, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'name'", null));
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            executor.shutdownNow();
        }
    }

    private static WorkInfo awaitState(WorkManager manager, WorkInfo.State state, int attempts) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            List<WorkInfo> work = manager.getWorkInfosForUniqueWork("tag-translation-key-rebuild").get(10, TimeUnit.SECONDS);
            if (work.size() == 1 && work.get(0).getState() == state
                && work.get(0).getRunAttemptCount() >= attempts) return work.get(0);
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Work did not reach " + state + " after " + attempts + " attempts");
    }
}
