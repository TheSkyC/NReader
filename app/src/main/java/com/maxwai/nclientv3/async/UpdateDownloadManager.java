// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdateDownloadRecord;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Owns update downloads for the lifetime of the application process. */
public final class UpdateDownloadManager {
    private static final ConcurrentHashMap<String, Call> ACTIVE = new ConcurrentHashMap<>();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .callTimeout(15L, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build();
    private UpdateDownloadManager() {}

    public static boolean isActive(@Nullable String path) {
        return path != null && ACTIVE.containsKey(path);
    }

    static void start(@NonNull Context context, @NonNull String version, @NonNull String url,
                      @Nullable String sha256, long size, @Nullable File folder) {
        Context app = context.getApplicationContext();
        UpdateStateStore store = new UpdateStateStore(app);
        if (!VersionChecker.GitHubRelease.isVersionAtLeast(version, version)
            || !VersionChecker.isAllowedUpdateUrl(url) || !VersionChecker.isValidSha256(sha256)) {
            store.runIfDownloadable(version, () -> publish(app, store,
                UpdateState.error(version, null, "download", System.currentTimeMillis())));
            return;
        }
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
            store.runIfDownloadable(version, () -> publish(app, store,
                UpdateState.error(version, null, "download", System.currentTimeMillis())));
            return;
        }
        String suffix = BuildConfig.FLAVOR.equals("minApi26") ? "-minApi26" : "-minApi28";
        UUID taskId = UUID.randomUUID();
        File apk = new File(folder, "NReader-" + version + suffix + "-" + taskId + ".apk");
        File partial = new File(apk.getAbsolutePath() + ".part");
        String path = partial.getAbsolutePath();
        UpdateDownloadRecord record = new UpdateDownloadRecord(taskId, version, path, url, sha256, size);
        OkHttpClient client = url.regionMatches(true, 0, "http:", 0, 5)
            ? CLIENT.newBuilder().followRedirects(false).build() : CLIENT;
        Call call = client.newCall(new Request.Builder().url(url)
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME).build());
        boolean claimed = store.runIfDownloadable(version, () -> {
            ACTIVE.put(path, call);
            try {
                UpdateNotification.resetReady(app);
                if (!store.claimDownload(record, System.currentTimeMillis())) {
                    throw new IllegalStateException("Unable to claim update download");
                }
                UpdateNotification.publish(app, store.load());
            } catch (RuntimeException exception) {
                ACTIVE.remove(path, call);
                throw exception;
            }
        });
        if (!claimed) return;
        try {
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call failed, @NonNull IOException exception) {
                    try {
                        fail(app, store, partial, version, exception);
                    } finally {
                        ACTIVE.remove(path, failed);
                    }
                }

                @Override
                public void onResponse(@NonNull Call activeCall, @NonNull Response response) {
                    try (Response ignored = response) {
                        if (!store.runIfDownloading(path, () -> {})) return;
                        if (!UpdateDownloadTransfer.receive(response, partial, record.getSize(), record.getSha256(), (written, total) ->
                            store.runIfDownloading(path, () -> publish(app, store,
                                UpdateState.downloading(version, path, written, total, System.currentTimeMillis()))))) return;
                        store.runIfDownloading(path, () -> {
                            if (!partial.renameTo(apk)) throw new IllegalStateException("Unable to finalize downloaded update");
                            publish(app, store, UpdateState.readyToInstall(version, apk.getAbsolutePath(), apk.length(), System.currentTimeMillis()));
                        });
                    } catch (IOException | RuntimeException exception) {
                        fail(app, store, partial, version, exception);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        partial.delete();
                        ACTIVE.remove(path, activeCall);
                    }
                }
            });
        } catch (RuntimeException exception) {
            try {
                fail(app, store, partial, version, exception);
            } finally {
                ACTIVE.remove(path, call);
            }
        }
    }

    public static void cancel(@NonNull Context context) {
        Context app = context.getApplicationContext();
        UpdateStateStore store = new UpdateStateStore(app);
        UpdateState current = store.load();
        String path = current.getFilePath();
        if (current.getPhase() != UpdatePhase.DOWNLOADING || path == null) return;
        store.runIfDownloading(path, () -> {
            Call call = ACTIVE.remove(path);
            if (call == null) return;
            UpdateState state = store.load();
            publish(app, store, UpdateState.available(state.getVersion(), System.currentTimeMillis()));
            UpdateNotification.clear(app);
            call.cancel();
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        });
    }

    private static void fail(Context context, UpdateStateStore store, File partial, String version, Exception exception) {
        boolean owned = store.runIfDownloading(partial.getAbsolutePath(), () -> publish(context, store,
            UpdateState.error(version, partial.getAbsolutePath(), "download", System.currentTimeMillis())));
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
        if (owned) LogUtility.e("Update download failed: " + exception.getClass().getSimpleName());
    }

    private static void publish(Context context, UpdateStateStore store, UpdateState state) {
        store.save(state);
        UpdateNotification.publish(context, state);
    }
}
