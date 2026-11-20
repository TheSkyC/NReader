package com.theskyc.nreader.async;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

public final class TagCatalogSeedWorker extends Worker {
    private static final String WORK_NAME = "tag-catalog-seed";
    private static final String ASSET_DIRECTORY = "tag-catalog/";

    public TagCatalogSeedWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(Context context) {
        schedule(context, ExistingWorkPolicy.KEEP);
    }

    public static void scheduleAfterRestore(Context context) {
        schedule(context, ExistingWorkPolicy.REPLACE);
    }

    private static void schedule(Context context, ExistingWorkPolicy policy) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy,
                new OneTimeWorkRequest.Builder(TagCatalogSeedWorker.class).build());
        } catch (IllegalStateException ignored) {
            // WorkManager may be disabled in local test processes.
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        SQLiteDatabase database = Database.getDatabase();
        if (database == null) return Result.failure();
        try {
            install(database, getApplicationContext().getAssets(), this::isStopped);
            return Result.success();
        } catch (IOException | SQLiteException error) {
            if (!isStopped()) LogUtility.w("Bundled tag catalog installation failed", error);
            return Result.failure();
        }
    }

    static boolean install(SQLiteDatabase database, AssetManager assets, BooleanSupplier cancelled) throws IOException {
        int revision;
        try (InputStream input = assets.open(ASSET_DIRECTORY + "tagsVersion")) {
            revision = TagCatalog.readRevision(input);
        }
        if (!TagCatalog.needsBundled(database, revision)) return false;
        try (InputStream input = assets.open(ASSET_DIRECTORY + "tags.json", AssetManager.ACCESS_STREAMING)) {
            return TagCatalog.applyBundled(database, input, revision, TagCatalog.MIN_DIRECTORY_ROWS, cancelled);
        }
    }
}
