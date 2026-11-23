package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.work.Data;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationKeyRebuildWorkerTest {
    @Test public void failedBatchRetriesFromCommittedCheckpointAfterReopen() {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        context.deleteDatabase("Entries.db");
        try {
            ReflectionHelpers.setStaticField(Database.class, "database", null);
            assertEquals(Result.retry(), worker(context).doWork());
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                ReflectionHelpers.setStaticField(Database.class, "database", database);
                for (int id = 1; id <= 201; id++) {
                    database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                        + " VALUES (?, 'ja', 'name', 'old')", new Object[]{id});
                }
                database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage) VALUES (1, 0)");
                database.execSQL("CREATE TRIGGER fail_rebuild BEFORE UPDATE ON TagTranslations"
                    + " WHEN OLD.idTag = 101 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
                assertEquals(Result.retry(), worker(context).doWork());
                assertTrue(TagTranslationCatalog.isKeyRebuildPending(database));
                assertEquals(100, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'name'", null));
                assertEquals(100, DatabaseUtils.longForQuery(database,
                    "SELECT lastRowId FROM TagTranslationKeyRebuild", null));
                database.execSQL("DROP TRIGGER fail_rebuild");
            }
            try (DatabaseHelper helper = new DatabaseHelper(context)) {
                SQLiteDatabase database = helper.getWritableDatabase();
                ReflectionHelpers.setStaticField(Database.class, "database", database);
                assertEquals(Result.success(), worker(context).doWork());
                assertFalse(TagTranslationCatalog.isKeyRebuildPending(database));
                assertEquals(201, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'name'", null));
            }
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
        }
    }

    private static TagTranslationKeyRebuildWorker worker(Application context) {
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new LegacyWorkerFactory(),
            (app, id, data) -> Futures.immediateFuture(null), (app, id, info) -> Futures.immediateFuture(null));
        return new TagTranslationKeyRebuildWorker(context, parameters);
    }
}
