package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.theskyc.nreader.TagRecoveryProcess;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class TagTranslationRebuildProcessSetupTest {
    @Test public void holdsFailedRebuildForExternalProcessKill() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("rebuild-process");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.theskyc.nreader.tagbenchmark", context.getPackageName());
        assertTrue(context.deleteDatabase("Entries.db") || !context.getDatabasePath("Entries.db").exists());
        DatabaseHelper helper = new DatabaseHelper(context);
        SQLiteDatabase database = helper.getWritableDatabase();
        Database.setDatabase(database);
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (700001, 'rule', 3, 1)");
        database.execSQL("INSERT INTO AccountTagBlacklist (idTag) VALUES (700001)");
        database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 7)");
        for (int id = 1; id <= 201; id++) {
            database.execSQL("INSERT INTO TagTranslations (idTag, locale, displayName, displayKey)"
                + " VALUES (?, 'ja', 'name', 'old')", new Object[]{id});
        }
        database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
            + " VALUES (1, 'ja', 'alias', 'old', 0)");
        database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage) VALUES (1, 0)");
        database.execSQL("CREATE TRIGGER fail_rebuild BEFORE UPDATE ON TagTranslations"
            + " WHEN OLD.idTag = 101 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        WorkManager.initialize(context, new Configuration.Builder().setWorkerFactory(new LegacyWorkerFactory()).build());
        WorkManager manager = WorkManager.getInstance(context);
        TagTranslationKeyRebuildWorker.schedule(context);
        WorkInfo retry = awaitRetry(manager);
        assertTrue(TagTranslationCatalog.isKeyRebuildPending(database));
        assertEquals(100, DatabaseUtils.longForQuery(database,
            "SELECT COUNT(*) FROM TagTranslations WHERE displayKey = 'name'", null));
        assertEquals(100, DatabaseUtils.longForQuery(database,
            "SELECT lastRowId FROM TagTranslationKeyRebuild", null));
        database.execSQL("DROP TRIGGER fail_rebuild");
        assertTrue(context.getSharedPreferences("tag-rebuild-process-probe", 0).edit()
            .putString("token", token).putString("workId", retry.getId().toString()).commit());
        TagRecoveryProcess.awaitExternalStop(token);
    }

    private static WorkInfo awaitRetry(WorkManager manager) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        do {
            List<WorkInfo> work = manager.getWorkInfosForUniqueWork("tag-translation-key-rebuild")
                .get(10, TimeUnit.SECONDS);
            if (work.size() == 1 && work.get(0).getState() == WorkInfo.State.ENQUEUED
                && work.get(0).getRunAttemptCount() > 0) return work.get(0);
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Rebuild did not reach retry after the injected failure");
    }
}
