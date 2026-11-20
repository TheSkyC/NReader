// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.utility.LogUtility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ScrapeTags extends Worker {
    private static final long CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;
    private static final String DATA_FOLDER = "https://raw.githubusercontent.com/maxwai/NClientV3/main/data/";
    private static final String TAGS = DATA_FOLDER + "tags.json";
    private static final String VERSION = DATA_FOLDER + "tagsVersion";
    private static final String WORK_NAME = "tag-catalog-refresh";
    private static final String CHECKED_REVISION = "checked_revision";
    private static final String CHECKED_AT = "checked_at";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build();
    private static boolean cleanedStagingDirectory;

    private volatile Call activeCall;

    public ScrapeTags(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void startWork(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScrapeTags.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        SQLiteDatabase database = Database.getDatabase();
        if (database == null) return failureOrRetry();
        SharedPreferences checks = getApplicationContext().getSharedPreferences("TagCatalogChecks", Context.MODE_PRIVATE);
        try {
            int installed = TagCatalog.installedRevision(database);
            long elapsed = System.currentTimeMillis() - checks.getLong(CHECKED_AT, 0);
            if (installed > 0 && installed == checks.getInt(CHECKED_REVISION, 0)
                && elapsed >= 0 && elapsed < CHECK_INTERVAL_MS) {
                return Result.success();
            }

            int revision = fetchRevision();
            if (revision > installed) downloadAndApply(database, revision);
            checkStopped();
            // Record only the revision this check could have verified. Restore can run concurrently.
            checks.edit().putInt(CHECKED_REVISION, Math.max(installed, revision))
                .putLong(CHECKED_AT, System.currentTimeMillis()).apply();
            return Result.success();
        } catch (IOException | SQLiteException error) {
            if (isStopped()) return Result.failure();
            LogUtility.w("Tag catalog refresh failed", error);
            return failureOrRetry();
        }
    }

    private void downloadAndApply(SQLiteDatabase database, int revision) throws IOException {
        File file = createStagingFile(getApplicationContext());
        try {
            try (OutputStream output = new FileOutputStream(file)) {
                download(TAGS, output, MAX_CATALOG_BYTES);
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
        if (!directory.isDirectory() && !directory.mkdir()) {
            throw new IOException("Could not create tag catalog staging directory");
        }
        if (!cleanedStagingDirectory) {
            // No import from this process can own a staging file yet.
            File[] abandoned = directory.listFiles((parent, name) -> name.endsWith(".part"));
            if (abandoned == null) throw new IOException("Could not read tag catalog staging directory");
            for (File file : abandoned) {
                if (!file.delete()) LogUtility.w("Could not remove abandoned tag catalog");
            }
            cleanedStagingDirectory = true;
        }
        return File.createTempFile("catalog-", ".part", directory);
    }

    private int fetchRevision() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32);
        download(VERSION, output, 32);
        return TagCatalog.readRevision(new ByteArrayInputStream(output.toByteArray()));
    }

    private void download(String url, OutputStream output, int limit) throws IOException {
        Call call = CLIENT.newCall(new Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build());
        activeCall = call;
        try {
            checkStopped();
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("Tag catalog request failed with HTTP " + response.code());
                }
                if (body.contentLength() > limit) throw new IOException("Tag catalog response exceeds limit");
                byte[] buffer = new byte[8192];
                int total = 0;
                try (InputStream input = body.byteStream()) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkStopped();
                        total += count;
                        if (total > limit) throw new IOException("Tag catalog response exceeds limit");
                        output.write(buffer, 0, count);
                    }
                }
            }
        } finally {
            activeCall = null;
        }
    }

    private Result failureOrRetry() {
        return getRunAttemptCount() + 1 < MAX_ATTEMPTS ? Result.retry() : Result.failure();
    }

    private void checkStopped() throws InterruptedIOException {
        if (isStopped() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Tag catalog update stopped");
        }
    }

    @Override
    public void onStopped() {
        Call call = activeCall;
        if (call != null) call.cancel();
    }
}
