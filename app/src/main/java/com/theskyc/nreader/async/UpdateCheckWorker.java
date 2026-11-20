// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Low-frequency, UI-free release check scheduled by the application. */
public final class UpdateCheckWorker extends Worker {
    private static final String WORK_NAME = "nreader_update_check";
    private static final long INTERVAL_HOURS = 24L;
    private final BooleanSupplier sourceEnabled;
    private final BooleanSupplier reconcileDownload;
    private final BooleanSupplier shouldCheck;
    private final Supplier<String> releaseUrl;
    private final Object lifecycleLock = new Object();
    @Nullable private volatile Call activeCall;
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
        this(context, parameters, sourceEnabled, reconcileDownload,
            () -> Global.shouldCheckForUpdates(context), UpdateCheckWorker::releaseApiUrl);
    }

    UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters parameters, @NonNull BooleanSupplier sourceEnabled,
                      @NonNull BooleanSupplier reconcileDownload, @NonNull BooleanSupplier shouldCheck,
                      @NonNull Supplier<String> releaseUrl) {
        super(context, parameters);
        this.sourceEnabled = sourceEnabled;
        this.reconcileDownload = reconcileDownload;
        this.shouldCheck = shouldCheck;
        this.releaseUrl = releaseUrl;
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
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            );
        } catch (IllegalStateException ignored) {
            // WorkManager may be intentionally disabled in local/unit-test processes.
        }
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
        if (isStopped()) return Result.retry();
        if (!sourceEnabled.getAsBoolean()) return Result.success();
        if (!reconcileDownload.getAsBoolean()) return Result.retry();
        UpdateStateStore stateStore = new UpdateStateStore(context);
        UpdateMetadataStore metadataStore = new UpdateMetadataStore(context);
        long readyRevision = stateStore.revision();
        stateStore.runIfUnchanged(readyRevision,
            () -> runIfActive(() -> publishReadyIfUnchanged(context, stateStore, metadataStore, readyRevision)));
        if (isStopped()) return Result.retry();
        if (!shouldCheck.getAsBoolean()) return Result.success();
        long revision = stateStore.revision();
        long[] generation = {-1L};
        if (!stateStore.runIfCheckableAndUnchanged(revision, () -> {
            generation[0] = UpdateCheckCoordinator.beginIfActive(this::runIfActive,
                () -> metadataStore.markAttempt(System.currentTimeMillis()));
        })) return Result.success();
        if (generation[0] < 0L) return Result.retry();
        try {
            String json = fetchReleaseJson(metadataStore, false, releaseUrl.get(), generation[0]);
            VersionChecker.GitHubRelease release;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                release = VersionChecker.parseVersionJson(reader, Global.isEnableBeta(), BuildConfig.FLAVOR);
            }
            if (release == null) {
                completeCheckIfUnchanged(context, stateStore, metadataStore, revision, generation[0], null, this::runIfActive);
                return Result.success();
            }
            if (release.manifestUrl != null) {
                VersionChecker.GitHubRelease manifestRelease = fetchManifest(release, context);
                if (manifestRelease != null) release = manifestRelease;
            }
            if (release.downloadUrl == null || !release.isNewerThanInstalled(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                completeCheckIfUnchanged(context, stateStore, metadataStore, revision, generation[0], null, this::runIfActive);
                return Result.success();
            }
            completeCheckIfUnchanged(context, stateStore, metadataStore, revision, generation[0], release, this::runIfActive);
            return Result.success();
        } catch (SupersededCheckException exception) {
            return Result.success();
        } catch (IOException | RuntimeException exception) {
            if (isStopped()) return Result.retry();
            return UpdateCheckCoordinator.isCurrent(generation[0]) ? Result.retry() : Result.success();
        }
    }

    @Override
    public void onStopped() {
        Call call = activeCall;
        if (call != null) call.cancel();
        // Wait for a state or cache publication that started before the stop.
        synchronized (lifecycleLock) { }
    }

    private boolean runIfActive(@NonNull Runnable action) {
        synchronized (lifecycleLock) {
            if (isStopped()) return false;
            action.run();
            return true;
        }
    }

    private interface CompletionGate {
        boolean run(@NonNull Runnable action);
    }

    static boolean completeCheckIfUnchanged(
        @NonNull Context context,
        @NonNull UpdateStateStore stateStore,
        @NonNull UpdateMetadataStore metadataStore,
        long revision,
        long generation,
        @Nullable VersionChecker.GitHubRelease selected
    ) {
        return completeCheckIfUnchanged(context, stateStore, metadataStore, revision, generation, selected, action -> {
            action.run();
            return true;
        });
    }

    private static boolean completeCheckIfUnchanged(
        @NonNull Context context, @NonNull UpdateStateStore stateStore, @NonNull UpdateMetadataStore metadataStore,
        long revision, long generation, @Nullable VersionChecker.GitHubRelease selected, @NonNull CompletionGate gate
    ) {
        boolean[] completed = {false};
        stateStore.runIfCheckableAndUnchanged(revision, () -> UpdateCheckCoordinator.runIfCurrent(generation, () -> completed[0] = gate.run(() -> {
            if (selected != null) {
                publishIfUnchanged(context, stateStore, metadataStore, revision, selected);
            } else {
                metadataStore.clearSelectedRelease();
                UpdateNotification.clearAvailable(context);
                if (stateStore.load().getPhase() == UpdatePhase.AVAILABLE) {
                    stateStore.save(UpdateState.idle(System.currentTimeMillis()));
                }
            }
            metadataStore.markSuccessfulCheck(System.currentTimeMillis());
        })));
        return completed[0];
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
                if (!selected.versionCode.equals(metadataStore.getSelectedVersion())) {
                    UpdateNotification.clearAvailable(context);
                }
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
    String fetchReleaseJson(@NonNull UpdateMetadataStore metadataStore, boolean unconditional, @NonNull String url) throws IOException {
        return fetchReleaseJson(metadataStore, unconditional, url, UpdateCheckCoordinator.begin());
    }

    @NonNull
    private String fetchReleaseJson(@NonNull UpdateMetadataStore metadataStore, boolean unconditional, @NonNull String url,
                                    long generation) throws IOException {
        UpdateMetadataStore.CacheSnapshot cache = metadataStore.cacheSnapshot();
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);
        if (!unconditional && cache.etag != null) builder.header("If-None-Match", cache.etag);
        Call call = UpdateRequestClient.newCall(client, builder.build());
        activeCall = call;
        if (isStopped()) call.cancel();
        try (Response response = call.execute()) {
            if (isStopped()) throw new IOException("Update check stopped");
            if (!UpdateCheckCoordinator.isCurrent(generation)) throw new SupersededCheckException();
            if (response.code() == 304 && unconditional) throw new IOException("Unexpected HTTP 304");
            String cached = response.code() == 304 && cache.etag != null ? cache.body : null;
            if (cached != null) {
                try {
                    VersionChecker.parseReleaseList(cached, Global.isEnableBeta());
                    return cached;
                } catch (IOException | RuntimeException ignored) { }
            }
            if (response.code() != 304 || unconditional) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                String body = UpdateResponseReader.read(response.body(), UpdateResponseReader.RELEASE_LIMIT);
                VersionChecker.parseReleaseList(body, Global.isEnableBeta());
                String etag = response.header("ETag");
                boolean[] saved = {false};
                if (!UpdateCheckCoordinator.runIfCurrent(generation,
                    () -> saved[0] = runIfActive(() -> metadataStore.save(body, etag)))) {
                    throw new SupersededCheckException();
                }
                if (!saved[0]) throw new IOException("Update check stopped");
                return body;
            }
        } finally {
            if (activeCall == call) activeCall = null;
        }
        return fetchReleaseJson(metadataStore, true, url, generation);
    }

    private static final class SupersededCheckException extends IOException { }

    private VersionChecker.GitHubRelease fetchManifest(
        @NonNull VersionChecker.GitHubRelease apiRelease,
        @NonNull Context context
    ) throws IOException {
        if (apiRelease.manifestUrl == null || !VersionChecker.isAllowedUpdateUrl(apiRelease.manifestUrl)) {
            return null;
        }
        Call call = UpdateRequestClient.newCall(client, new Request.Builder()
            .url(apiRelease.manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build());
        activeCall = call;
        if (isStopped()) call.cancel();
        try (Response response = call.execute()) {
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
        } finally {
            if (activeCall == call) activeCall = null;
        }
    }

    @NonNull
    private static String releaseApiUrl() {
        if (!BuildConfig.UPDATE_ENDPOINT.isEmpty()) return BuildConfig.UPDATE_ENDPOINT;
        return "https://api.github.com/repos/" + BuildConfig.UPDATE_REPOSITORY + "/releases";
    }
}
