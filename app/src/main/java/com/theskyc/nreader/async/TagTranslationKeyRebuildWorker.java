package com.theskyc.nreader.async;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.IOException;

public final class TagTranslationKeyRebuildWorker extends Worker {
    private static final String WORK_NAME = "tag-translation-key-rebuild";

    public TagTranslationKeyRebuildWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(Context context) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(TagTranslationKeyRebuildWorker.class).build());
        } catch (IllegalStateException error) {
            LogUtility.w("Unable to schedule translation key rebuild", error);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        SQLiteDatabase database = Database.getDatabase();
        if (database == null) return Result.retry();
        try {
            while (!TagTranslationCatalog.rebuildUnicodeKeyBatch(database, this::isStopped)) {
                if (isStopped()) return Result.retry();
            }
            return Result.success();
        } catch (IOException | SQLiteException | IllegalStateException error) {
            if (!isStopped()) LogUtility.w("Translation key rebuild failed; retrying", error);
            return Result.retry();
        }
    }
}
