// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.async.update.UpdateDownloadRecord;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;

import java.io.File;
import java.util.UUID;

/** Claims and cancels update downloads scheduled independently of the screen. */
public final class UpdateDownloadManager {
    private UpdateDownloadManager() {}

    public static boolean isActive(@Nullable String path) {
        return UpdateDownloadWorker.isActive(path);
    }

    static void start(@NonNull Context context, @NonNull String version, @NonNull String url,
                      @Nullable String sha256, long size, @Nullable File folder) {
        Context app = context.getApplicationContext();
        UpdateStateStore store = new UpdateStateStore(app);
        if (!VersionChecker.GitHubRelease.isVersionAtLeast(version, version)
            || !VersionChecker.isAllowedUpdateUrl(url) || !VersionChecker.isValidSha256(sha256)
            || folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
            store.runIfDownloadable(version, () -> {
                UpdateState error = UpdateState.error(version, null, "download", System.currentTimeMillis());
                store.save(error);
                UpdateNotification.publish(app, error);
            });
            return;
        }
        String suffix = BuildConfig.FLAVOR.equals("minApi26") ? "-minApi26" : "-minApi28";
        UUID id = UUID.randomUUID();
        File partial = new File(folder, "NReader-" + version + suffix + "-" + id + ".apk.part");
        UpdateDownloadRecord record = new UpdateDownloadRecord(id, version, partial.getAbsolutePath(), url, sha256, size);
        if (!store.claimScheduledDownload(record, System.currentTimeMillis())) return;
        store.runIfScheduledDownload(id, () -> {
            UpdateNotification.resetReady(app);
            UpdateNotification.clear(app);
            UpdateNotification.publish(app, store.load());
        });
        UpdateDownloadScheduler.enqueue(app, id);
    }

    public static void cancel(@NonNull Context context) {
        Context app = context.getApplicationContext();
        UpdateStateStore store = new UpdateStateStore(app);
        UpdateDownloadRecord record = store.loadDownloadRecord();
        if (record == null) return;
        if (store.runIfScheduledDownload(record.getId(), () -> {
            store.recordCancellation(record);
            store.save(UpdateState.available(record.getVersion(), System.currentTimeMillis()));
            UpdateNotification.clear(app);
        })) UpdateDownloadScheduler.cancel(app, record);
    }
}
