// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.async.database.TagCatalogManifest;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;

public class ScrapeTags extends Worker {
    private static final long CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_ATTEMPTS = 3;
    private static final String DATA_FOLDER = "https://raw.githubusercontent.com/maxwai/NClientV3/main/data/";
    private static final String TAGS = DATA_FOLDER + "tags.json";
    private static final String VERSION = DATA_FOLDER + "tagsVersion";
    private static final String WORK_NAME = "tag-catalog-refresh";
    static final String FORCE_CHECK = "force_check";
    public static final String CHECK_RESULT = "catalog_check_result";
    public static final String CHECKED = "checked";
    public static final String NO_SOURCE = "no_source";
    private static final String CHECKED_REVISION = "checked_revision";
    private static final String CHECKED_AT = "checked_at";
    private static final Set<File> CLEANED_STAGING_DIRECTORIES = new HashSet<>();

    private final TagFeedDownload download;
    private final String manifestUrl;

    public ScrapeTags(@NonNull Context context, @NonNull WorkerParameters params) {
        this(context, params, BuildConfig.TAG_CATALOG_MANIFEST_URL,
            request -> TagFeedDownload.publicClient().newCall(request));
    }

    ScrapeTags(Context context, WorkerParameters params, String manifestUrl, Call.Factory client) {
        super(context, params);
        this.manifestUrl = manifestUrl;
        this.download = new TagFeedDownload(client, this::isStopped);
    }

    public static void startWork(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScrapeTags.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    public static Operation requestRefresh(Context context, UUID id) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScrapeTags.class)
            .setId(id)
            .setInputData(new Data.Builder().putBoolean(FORCE_CHECK, true).build())
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        return WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        return refresh(Database.getDatabase());
    }

    Result refresh(SQLiteDatabase database) {
        if (database == null) return failureOrRetry();
        boolean published = !manifestUrl.isEmpty();
        try {
            int publishedRevision = TagCatalog.installedPublishedRevision(database);
            if (!published && publishedRevision > 0) return success(NO_SOURCE);
            int installed = published ? publishedRevision : TagCatalog.installedRevision(database);
            SharedPreferences checks = getApplicationContext().getSharedPreferences(
                published ? "PublishedTagCatalogChecks" : "TagCatalogChecks", Context.MODE_PRIVATE);
            long elapsed = System.currentTimeMillis() - checks.getLong(CHECKED_AT, 0);
            if (!getInputData().getBoolean(FORCE_CHECK, false)
                && installed > 0 && installed == checks.getInt(CHECKED_REVISION, 0)
                && elapsed >= 0 && elapsed < CHECK_INTERVAL_MS) {
                return Result.success();
            }

            int revision = published ? checkPublished(database, installed) : checkLegacy(database, installed);
            download.checkCancelled();
            // Record only the revision this check verified; another installer may run concurrently.
            checks.edit().putInt(CHECKED_REVISION, revision)
                .putLong(CHECKED_AT, System.currentTimeMillis()).apply();
            return success(CHECKED);
        } catch (IOException | SQLiteException error) {
            if (isStopped()) return Result.failure();
            LogUtility.w("Tag catalog refresh failed", error);
            return failureOrRetry();
        }
    }

    private Result success(String checkResult) {
        if (!getInputData().getBoolean(FORCE_CHECK, false)) return Result.success();
        return Result.success(new Data.Builder().putString(CHECK_RESULT, checkResult).build());
    }

    private int checkLegacy(SQLiteDatabase database, int installed) throws IOException {
        int revision = fetchRevision();
        if (revision < installed) throw new IOException("Legacy tag catalog revision is stale");
        if (revision > installed) downloadAndApply(database, revision);
        return revision;
    }

    private int checkPublished(SQLiteDatabase database, int installed) throws IOException {
        HttpUrl url = TagCatalogManifest.manifestUrl(manifestUrl);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        download.copy(url, output, TagCatalogManifest.MAX_BYTES, true);
        TagCatalogManifest manifest = TagCatalogManifest.parse(output.toByteArray());
        if (manifest.revision < installed) throw new IOException("Published tag catalog revision is stale");
        if (manifest.revision == installed) return installed;

        File file = createStagingFile(getApplicationContext());
        try {
            try (OutputStream staged = new FileOutputStream(file)) {
                download.copy(manifest.payloadUrl(url), staged, manifest.sizeBytes, true);
            }
            manifest.apply(database, file, this::isStopped);
        } finally {
            if (!file.delete()) LogUtility.w("Could not remove staged tag catalog");
        }
        return manifest.revision;
    }

    private void downloadAndApply(SQLiteDatabase database, int revision) throws IOException {
        File file = createStagingFile(getApplicationContext());
        try {
            try (OutputStream output = new FileOutputStream(file)) {
                download.copy(HttpUrl.get(TAGS), output, TagCatalogManifest.MAX_PAYLOAD_BYTES, false);
            }
            if (fetchRevision() != revision) throw new IOException("Tag catalog revision changed during download");
            try (InputStream input = new FileInputStream(file)) {
                TagCatalog.apply(database, input, revision, TagCatalog.MIN_DIRECTORY_ROWS, this::isStopped);
            }
        } finally {
            if (!file.delete()) LogUtility.w("Could not remove staged tag catalog");
        }
    }

    private static synchronized File createStagingFile(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "tag-catalog");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create tag catalog staging directory");
        }
        if (!CLEANED_STAGING_DIRECTORIES.contains(directory)) {
            // No import from this process can own a staging file yet.
            File[] abandoned = directory.listFiles((parent, name) -> name.endsWith(".part"));
            if (abandoned == null) throw new IOException("Could not read tag catalog staging directory");
            for (File file : abandoned) {
                if (!file.delete()) LogUtility.w("Could not remove abandoned tag catalog");
            }
            CLEANED_STAGING_DIRECTORIES.add(directory);
        }
        return File.createTempFile("catalog-", ".part", directory);
    }

    private int fetchRevision() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32);
        download.copy(HttpUrl.get(VERSION), output, 32, false);
        return TagCatalog.readRevision(new ByteArrayInputStream(output.toByteArray()));
    }

    private Result failureOrRetry() {
        return getRunAttemptCount() + 1 < MAX_ATTEMPTS ? Result.retry() : Result.failure();
    }

    @Override
    public void onStopped() {
        download.cancelActiveCall();
    }
}
