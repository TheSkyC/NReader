// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Low-frequency, UI-free release check scheduled by the application. */
public final class UpdateCheckWorker extends Worker {
    private static final String WORK_NAME = "nreader_update_check";
    private static final long INTERVAL_HOURS = 24L;
    private final OkHttpClient client = new OkHttpClient.Builder().build();

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void schedule(@NonNull Context context) {
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty()) return;
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            UpdateCheckWorker.class,
            INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1L, TimeUnit.HOURS)
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!Global.shouldCheckForUpdates(context)) return Result.success();
        UpdateStateStore stateStore = new UpdateStateStore(context);
        UpdateMetadataStore metadataStore = new UpdateMetadataStore(context);
        stateStore.save(UpdateState.checking(System.currentTimeMillis()));
        try {
            String json = fetchReleaseJson(metadataStore);
            VersionChecker.GitHubRelease release;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                release = VersionChecker.parseVersionJson(reader, Global.isEnableBeta(), BuildConfig.FLAVOR);
            }
            if (release == null) {
                stateStore.save(UpdateState.idle(System.currentTimeMillis()));
                return Result.success();
            }
            if (release.manifestUrl != null) {
                VersionChecker.GitHubRelease manifestRelease = fetchManifest(release, context);
                if (manifestRelease != null) release = manifestRelease;
            }
            if (release.downloadUrl == null || !release.isNewerThenVersion(BuildConfig.VERSION_NAME)) {
                stateStore.save(UpdateState.idle(System.currentTimeMillis()));
                return Result.success();
            }
            metadataStore.setSelectedSha256(release.sha256);
            UpdateState available = UpdateState.available(release.versionCode, System.currentTimeMillis());
            stateStore.save(available);
            UpdateNotification.publish(context, available);
            return Result.success();
        } catch (IOException | RuntimeException exception) {
            stateStore.save(UpdateState.error(null, null, "background_metadata", System.currentTimeMillis()));
            return Result.retry();
        }
    }

    @NonNull
    private String fetchReleaseJson(@NonNull UpdateMetadataStore metadataStore) throws IOException {
        Request.Builder builder = new Request.Builder()
            .url("https://api.github.com/repos/" + BuildConfig.UPDATE_REPOSITORY + "/releases")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);
        if (metadataStore.getEtag() != null) builder.header("If-None-Match", metadataStore.getEtag());
        try (Response response = client.newCall(builder.build()).execute()) {
            if (response.code() == 304 && metadataStore.getBody() != null) return metadataStore.getBody();
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            if (response.body().contentLength() > 2L * 1024L * 1024L) throw new IOException("Metadata too large");
            String body = response.body().string();
            metadataStore.save(body, response.header("ETag"));
            return body;
        }
    }

    private VersionChecker.GitHubRelease fetchManifest(
        @NonNull VersionChecker.GitHubRelease apiRelease,
        @NonNull Context context
    ) throws IOException {
        try (Response response = client.newCall(new Request.Builder()
            .url(apiRelease.manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build()).execute()) {
            if (!response.isSuccessful() || response.body() == null
                || response.body().contentLength() > 1024L * 1024L) return null;
            try (JsonReader reader = new JsonReader(new StringReader(response.body().string()))) {
                return VersionChecker.parseManifestJson(
                    reader,
                    Global.isEnableBeta(),
                    BuildConfig.FLAVOR,
                    context.getPackageName()
                );
            }
        }
    }
}
