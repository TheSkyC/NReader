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
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Low-frequency, UI-free release check scheduled by the application. */
public final class UpdateCheckWorker extends Worker {
    private static final String WORK_NAME = "nreader_update_check";
    private static final long INTERVAL_HOURS = 24L;
    private final BooleanSupplier sourceEnabled;
    private final BooleanSupplier reconcileDownload;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .callTimeout(15L, TimeUnit.MINUTES)
        .build();

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        this(context, parameters, () -> !BuildConfig.UPDATE_REPOSITORY.isEmpty() || !BuildConfig.UPDATE_ENDPOINT.isEmpty(),
            () -> UpdateDownloadScheduler.reconcileForWorker(context));
    }

    UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters, @NonNull BooleanSupplier sourceEnabled,
                      @NonNull BooleanSupplier reconcileDownload) {
        super(context, parameters);
        this.sourceEnabled = sourceEnabled;
        this.reconcileDownload = reconcileDownload;
    }

    public static void schedule(@NonNull Context context) {
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty() && BuildConfig.UPDATE_ENDPOINT.isEmpty()) {
            cancelScheduledWork(context);
            return;
        }
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

    private static void cancelScheduledWork(@NonNull Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        } catch (IllegalStateException ignored) {
            // WorkManager may be intentionally disabled in local/unit-test processes.
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!sourceEnabled.getAsBoolean()) return Result.success();
        if (!reconcileDownload.getAsBoolean()) return Result.retry();
        UpdateStateStore stateStore = new UpdateStateStore(context);
        UpdateMetadataStore metadataStore = new UpdateMetadataStore(context);
        publishReadyIfUnchanged(context, stateStore, metadataStore, stateStore.revision());
        if (!Global.shouldCheckForUpdates(context)) return Result.success();
        long revision = stateStore.revision();
        UpdatePhase phase = stateStore.load().getPhase();
        if (phase != UpdatePhase.IDLE && phase != UpdatePhase.AVAILABLE) return Result.success();
        metadataStore.markAttempt(System.currentTimeMillis());
        try {
            String json = fetchReleaseJson(metadataStore);
            VersionChecker.GitHubRelease release;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                release = VersionChecker.parseVersionJson(reader, Global.isEnableBeta(), BuildConfig.FLAVOR);
            }
            if (release == null) {
                metadataStore.markSuccessfulCheck(System.currentTimeMillis());
                return Result.success();
            }
            if (release.manifestUrl != null) {
                VersionChecker.GitHubRelease manifestRelease = fetchManifest(release, context);
                if (manifestRelease != null) release = manifestRelease;
            }
            if (release.downloadUrl == null || !release.isNewerThanInstalled(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                metadataStore.markSuccessfulCheck(System.currentTimeMillis());
                return Result.success();
            }
            metadataStore.markSuccessfulCheck(System.currentTimeMillis());
            publishIfUnchanged(context, stateStore, metadataStore, revision, release);
            return Result.success();
        } catch (IOException | RuntimeException exception) {
            return Result.retry();
        }
    }

    static boolean publishReadyIfUnchanged(@NonNull Context context, @NonNull UpdateStateStore stateStore,
                                           @NonNull UpdateMetadataStore metadataStore, long revision) {
        return stateStore.runIfUnchanged(revision, () -> {
            UpdateState state = stateStore.load();
            if (state.getPhase() != UpdatePhase.READY_TO_INSTALL || state.getVersion() == null
                || state.getFilePath() == null || UpdateStateRecovery.isInstalled(metadataStore, state.getVersion())) return;
            boolean exists;
            try {
                exists = new File(state.getFilePath()).isFile();
            } catch (SecurityException exception) {
                LogUtility.w("Unable to inspect ready update: " + exception.getClass().getSimpleName());
                return;
            }
            if (exists) UpdateNotification.publish(context, state);
        });
    }

    static boolean publishIfUnchanged(
        @NonNull Context context,
        @NonNull UpdateStateStore stateStore,
        @NonNull UpdateMetadataStore metadataStore,
        long revision,
        @NonNull VersionChecker.GitHubRelease selected
    ) {
        return stateStore.runIfCheckableAndUnchanged(revision, () -> {
            UpdateState available = UpdateState.available(selected.versionCode, System.currentTimeMillis());
            UpdateState current = stateStore.load();
            if (current.getPhase() == UpdatePhase.IDLE || !selected.versionCode.equals(current.getVersion())
                || !selected.versionCode.equals(metadataStore.getSelectedVersion())) {
                metadataStore.saveSelectedRelease(
                    selected.versionCode,
                    selected.releaseVersionCode,
                    selected.downloadUrl,
                    selected.body,
                    selected.beta,
                    selected.sha256,
                    selected.sizeBytes
                );
                stateStore.save(available);
            }
            UpdateNotification.publish(context, available);
        });
    }

    @NonNull
    private String fetchReleaseJson(@NonNull UpdateMetadataStore metadataStore) throws IOException {
        return fetchReleaseJson(metadataStore, false);
    }

    @NonNull
    private String fetchReleaseJson(@NonNull UpdateMetadataStore metadataStore, boolean unconditional) throws IOException {
        Request.Builder builder = new Request.Builder()
            .url(releaseApiUrl())
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);
        if (!unconditional && metadataStore.getEtag() != null) builder.header("If-None-Match", metadataStore.getEtag());
        try (Response response = UpdateRequestClient.newCall(client, builder.build()).execute()) {
            if (response.code() == 304 && metadataStore.getBody() != null) return metadataStore.getBody();
            if (response.code() == 304) return fetchReleaseJson(metadataStore, true);
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            String body = UpdateResponseReader.read(response.body(), UpdateResponseReader.RELEASE_LIMIT);
            metadataStore.save(body, response.header("ETag"));
            return body;
        }
    }

    private VersionChecker.GitHubRelease fetchManifest(
        @NonNull VersionChecker.GitHubRelease apiRelease,
        @NonNull Context context
    ) throws IOException {
        if (apiRelease.manifestUrl == null || !VersionChecker.isAllowedUpdateUrl(apiRelease.manifestUrl)) {
            return null;
        }
        try (Response response = UpdateRequestClient.newCall(client, new Request.Builder()
            .url(apiRelease.manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            try (JsonReader reader = new JsonReader(new StringReader(
                UpdateResponseReader.read(response.body(), UpdateResponseReader.MANIFEST_LIMIT)
            ))) {
                return VersionChecker.parseManifestJson(
                    reader,
                    Global.isEnableBeta(),
                    BuildConfig.FLAVOR,
                    context.getPackageName()
                );
            } catch (IOException | RuntimeException exception) {
                return null;
            }
        }
    }

    @NonNull
    private static String releaseApiUrl() {
        if (!BuildConfig.UPDATE_ENDPOINT.isEmpty()) return BuildConfig.UPDATE_ENDPOINT;
        return "https://api.github.com/repos/" + BuildConfig.UPDATE_REPOSITORY + "/releases";
    }
}
