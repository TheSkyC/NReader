// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final long PROGRESS_INTERVAL_MS = 500L;

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
        File apk = new File(folder, "NReader-" + version + suffix + "-" + UUID.randomUUID() + ".apk");
        File partial = new File(apk.getAbsolutePath() + ".part");
        String path = partial.getAbsolutePath();
        OkHttpClient client = url.regionMatches(true, 0, "http:", 0, 5)
            ? CLIENT.newBuilder().followRedirects(false).build() : CLIENT;
        Call call = client.newCall(new Request.Builder().url(url)
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME).build());
        boolean claimed = store.runIfDownloadable(version, () -> {
            ACTIVE.put(path, call);
            try {
                UpdateNotification.resetReady(app);
                publish(app, store, UpdateState.downloading(version, path, 0L, size, System.currentTimeMillis()));
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
                        if (!response.isSuccessful() || response.body() == null) {
                            throw new IOException("Unable to download update: HTTP " + response.code());
                        }
                        if (!store.runIfDownloading(path, () -> {})) return;
                        long contentLength = response.body().contentLength();
                        if (contentLength > VersionChecker.MAX_UPDATE_SIZE_BYTES || size > VersionChecker.MAX_UPDATE_SIZE_BYTES) {
                            throw new IOException("Update package exceeds the maximum allowed size");
                        }
                        long total = size > 0L ? size : contentLength;
                        long written = 0L;
                        long lastProgress = SystemClock.elapsedRealtime();
                        try (FileOutputStream output = new FileOutputStream(partial);
                             InputStream input = response.body().byteStream()) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                output.write(buffer, 0, count);
                                written += count;
                                if (written > VersionChecker.MAX_UPDATE_SIZE_BYTES) {
                                    throw new IOException("Update package exceeds the maximum allowed size");
                                }
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                                    long progress = written;
                                    if (!store.runIfDownloading(path, () -> publish(app, store,
                                        UpdateState.downloading(version, path, progress, total, System.currentTimeMillis())))) return;
                                    lastProgress = now;
                                }
                            }
                            output.flush();
                        }
                        if (contentLength >= 0L && written != contentLength) {
                            throw new IOException("Downloaded update length does not match response");
                        }
                        if (size > 0L && written != size) {
                            throw new IOException("Downloaded update size does not match release metadata");
                        }
                        if (!UpdateFileVerifier.matchesSha256(partial, sha256)) {
                            throw new IOException("Downloaded update checksum does not match release metadata");
                        }
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
