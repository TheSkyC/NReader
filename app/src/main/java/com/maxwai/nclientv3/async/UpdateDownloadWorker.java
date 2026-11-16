// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdateDownloadRecord;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Executes a single system-scheduled update download. */
public final class UpdateDownloadWorker extends Worker {
    private static final int FOREGROUND_NOTIFICATION_ID = 1203;
    private static final ConcurrentHashMap<UUID, UpdateDownloadWorker> ACTIVE = new ConcurrentHashMap<>();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .callTimeout(15L, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build();
    private volatile Call call;
    private volatile String activePath;
    private final BooleanSupplier sourceEnabled;

    public UpdateDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        this(context, parameters, () -> !BuildConfig.UPDATE_REPOSITORY.isEmpty() || !BuildConfig.UPDATE_ENDPOINT.isEmpty());
    }

    UpdateDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters, @NonNull BooleanSupplier sourceEnabled) {
        super(context, parameters);
        this.sourceEnabled = sourceEnabled;
    }

    @NonNull
    @Override
    public Result doWork() {
        UUID id = getId();
        if (ACTIVE.putIfAbsent(id, this) != null) return Result.retry();
        Context context = getApplicationContext();
        UpdateStateStore store = new UpdateStateStore(context);
        UpdateDownloadRecord[] captured = new UpdateDownloadRecord[1];
        File partial = null;
        try {
            if (!store.runIfScheduledDownload(id, () -> captured[0] = store.loadDownloadRecord())) return Result.success();
            UpdateDownloadRecord record = captured[0];
            if (!sourceEnabled.getAsBoolean()) throw new IOException("Update source is disabled");
            if (!validRecord(record)) throw new IOException("Invalid update download record");
            partial = new File(record.getFilePath());
            activePath = record.getFilePath();
            boolean[] installed = {false};
            if (!store.runIfScheduledDownload(id, () -> {
                if (UpdateStateRecovery.isInstalled(new UpdateMetadataStore(context), record.getVersion())) {
                    store.save(UpdateStateRecovery.reconcile(context, store.load()));
                    installed[0] = true;
                }
            })) return Result.success();
            if (installed[0]) return Result.success();
            File apk = new File(record.getFilePath().substring(0, record.getFilePath().length() - 5));
            File canonicalApk = apk.getCanonicalFile();
            if (!apk.getName().equals(canonicalApk.getName())
                || !Global.UPDATEFOLDER.getCanonicalFile().equals(canonicalApk.getParentFile())) {
                throw new IOException("Invalid update artifact location");
            }
            if (isStopped()) return Result.retry();
            UpdateState initial = UpdateState.downloading(record.getVersion(), record.getFilePath(), 0L,
                record.getSize(), System.currentTimeMillis());
            setForegroundAsync(foreground(context, initial)).get(15L, TimeUnit.SECONDS);
            if (isStopped() || !store.runIfScheduledDownload(id, () -> {
                store.save(initial);
                UpdateNotification.clear(context);
            })) return Result.success();

            if (apk.exists()) {
                if (!apk.isFile() || (record.getSize() > 0L && apk.length() != record.getSize())
                    || !UpdateFileVerifier.matchesSha256(apk, record.getSha256())) {
                    throw new IOException("Existing update artifact failed verification");
                }
            } else {
                OkHttpClient client = record.getUrl().regionMatches(true, 0, "http:", 0, 5)
                    ? CLIENT.newBuilder().followRedirects(false).build() : CLIENT;
                call = client.newCall(new Request.Builder().url(record.getUrl())
                    .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME).build());
                if (isStopped()) call.cancel();
                try (Response response = call.execute()) {
                    if (!UpdateDownloadTransfer.receive(response, partial, record.getSize(), record.getSha256(),
                        (written, total) -> !isStopped() && store.runIfScheduledDownload(id, () -> {
                            UpdateState state = UpdateState.downloading(record.getVersion(), record.getFilePath(),
                                written, total, System.currentTimeMillis());
                            store.save(state);
                            setForegroundAsync(foreground(context, state));
                        }))) return Result.success();
                }
                if (isStopped()) return Result.retry();
                if (!store.runIfScheduledDownload(id, () -> {
                    if (isStopped()) return;
                    try {
                        Files.move(new File(record.getFilePath()).toPath(), apk.toPath());
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to finalize downloaded update", exception);
                    }
                    UpdateState ready = UpdateState.readyToInstall(record.getVersion(), apk.getAbsolutePath(),
                        apk.length(), System.currentTimeMillis());
                    store.save(ready);
                    UpdateNotification.publish(context, ready);
                })) return Result.success();
                return isStopped() ? Result.retry() : Result.success();
            }
            if (isStopped()) return Result.retry();
            store.runIfScheduledDownload(id, () -> {
                if (isStopped()) return;
                UpdateState ready = UpdateState.readyToInstall(record.getVersion(), apk.getAbsolutePath(),
                    apk.length(), System.currentTimeMillis());
                store.save(ready);
                UpdateNotification.publish(context, ready);
            });
            return Result.success();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (isStopped()) return Result.retry();
            store.runIfScheduledDownload(id, () -> {
                UpdateState current = store.load();
                UpdateState error = UpdateState.error(current.getVersion(), current.getFilePath(), "download", System.currentTimeMillis());
                store.save(error);
                UpdateNotification.publish(context, error);
            });
            LogUtility.w("Update download worker failed: " + exception.getClass().getSimpleName());
            return Result.failure();
        } finally {
            call = null;
            activePath = null;
            if (partial != null && partial.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            if (isStopped() && captured[0] != null) {
                UpdateDownloadRecord record = captured[0];
                store.runIfScheduledDownload(id, () -> {
                    UpdateState queued = UpdateState.queued(record.getVersion(), record.getFilePath(), record.getSize(), System.currentTimeMillis());
                    store.save(queued);
                    UpdateNotification.publish(context, queued);
                });
            }
            ACTIVE.remove(id, this);
            if (captured[0] != null) {
                for (UpdateDownloadRecord cancellation : store.loadCancellations()) {
                    if (id.equals(cancellation.getId())) {
                        UpdateDownloadScheduler.cancel(context, cancellation);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void onStopped() {
        Call active = call;
        if (active != null) active.cancel();
    }

    static void cancel(UUID id) {
        UpdateDownloadWorker worker = ACTIVE.get(id);
        if (worker != null) {
            Call active = worker.call;
            if (active != null) active.cancel();
        }
    }

    static boolean isActive(String path) {
        if (path == null) return false;
        for (UpdateDownloadWorker worker : ACTIVE.values()) {
            if (path.equals(worker.activePath)) return true;
        }
        return false;
    }

    private static ForegroundInfo foreground(Context context, UpdateState state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(FOREGROUND_NOTIFICATION_ID, UpdateNotification.foreground(context, state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(FOREGROUND_NOTIFICATION_ID, UpdateNotification.foreground(context, state));
    }

    static boolean validRecord(UpdateDownloadRecord record) throws IOException {
        if (record == null || Global.UPDATEFOLDER == null || !VersionChecker.GitHubRelease.isVersionAtLeast(record.getVersion(), record.getVersion())
            || !VersionChecker.isAllowedUpdateUrl(record.getUrl()) || !VersionChecker.isValidSha256(record.getSha256())
            || record.getSize() < -1L || record.getSize() > VersionChecker.MAX_UPDATE_SIZE_BYTES) return false;
        String suffix = BuildConfig.FLAVOR.equals("minApi26") ? "-minApi26" : "-minApi28";
        String expected = "NReader-" + record.getVersion() + suffix + "-" + record.getId() + ".apk.part";
        File file = new File(record.getFilePath());
        File canonical = file.getCanonicalFile();
        return expected.equals(file.getName()) && expected.equals(canonical.getName())
            && Global.UPDATEFOLDER.getCanonicalFile().equals(canonical.getParentFile());
    }
}
