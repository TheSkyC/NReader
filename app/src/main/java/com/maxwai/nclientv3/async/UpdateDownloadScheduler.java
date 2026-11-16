// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.maxwai.nclientv3.async.update.UpdateDownloadRecord;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Coordinates persisted update attempts with WorkManager without blocking the UI. */
final class UpdateDownloadScheduler {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String WORK_PREFIX = "nreader_update_download_";

    private UpdateDownloadScheduler() {}

    static void enqueue(@NonNull Context context, @NonNull UUID id) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> enqueueNow(app, id));
    }

    static void reconcile(@NonNull Context context) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            UpdateStateStore store = new UpdateStateStore(app);
            if (!store.isScheduledDownload()) return;
            UpdateDownloadRecord record = store.loadDownloadRecord();
            if (record == null) {
                long revision = store.revision();
                store.runIfUnchanged(revision, () -> {
                    if (store.isScheduledDownload() && store.loadDownloadRecord() == null) {
                        UpdateState current = store.load();
                        store.save(UpdateState.error(current.getVersion(), current.getFilePath(), "interrupted_download", System.currentTimeMillis()));
                        UpdateNotification.clear(app);
                    }
                });
                return;
            }
            long revision = store.revision();
            try {
                WorkInfo info = WorkManager.getInstance(app).getWorkInfoById(record.getId()).get(10L, TimeUnit.SECONDS);
                if (info == null) {
                    enqueueNow(app, record.getId());
                } else if (info.getState().isFinished()) {
                    store.runIfUnchanged(revision, () -> store.runIfScheduledDownload(record.getId(), () -> {
                        UpdateState current = store.load();
                        store.save(UpdateState.error(current.getVersion(), current.getFilePath(), "interrupted_download", System.currentTimeMillis()));
                        UpdateNotification.clear(app);
                    }));
                } else if (info.getState() == WorkInfo.State.ENQUEUED || info.getState() == WorkInfo.State.BLOCKED) {
                    store.runIfUnchanged(revision, () -> store.runIfScheduledDownload(record.getId(), () -> {
                        if (store.load().getPhase() != UpdatePhase.QUEUED) {
                            UpdateState queued = UpdateState.queued(record.getVersion(), record.getFilePath(), record.getSize(), System.currentTimeMillis());
                            store.save(queued);
                            UpdateNotification.publish(app, queued);
                        }
                    }));
                }
            } catch (IllegalStateException exception) {
                failIfCurrent(app, record.getId());
                LogUtility.w("Update work is unavailable: " + exception.getClass().getSimpleName());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                LogUtility.w("Unable to query update work: " + exception.getClass().getSimpleName());
            }
        });
    }

    static void cancel(@NonNull Context context, @NonNull UpdateDownloadRecord record) {
        Context app = context.getApplicationContext();
        UUID id = record.getId();
        UpdateDownloadWorker.cancel(id);
        EXECUTOR.execute(() -> {
            try {
                WorkManager.getInstance(app).cancelWorkById(id).getResult().get(10L, TimeUnit.SECONDS);
                cleanupCancellation(app, record);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                LogUtility.w("Unable to cancel update work: " + exception.getClass().getSimpleName());
            }
        });
    }

    static void cleanupCancellation(@NonNull Context context, @NonNull UpdateDownloadRecord fallback) {
        UpdateStateStore store = new UpdateStateStore(context);
        UpdateDownloadRecord record = fallback;
        if (UpdateDownloadWorker.isActive(record.getFilePath())) return;
        try {
            if (!UpdateDownloadWorker.validRecord(record)) return;
            File partial = new File(record.getFilePath());
            if (partial.exists() && (!partial.isFile() || !partial.delete())) return;
        } catch (Exception exception) {
            LogUtility.w("Unable to clean canceled update: " + exception.getClass().getSimpleName());
            return;
        }
        store.clearCancellationIf(record.getId());
    }

    private static void enqueueNow(Context context, UUID id) {
        UpdateStateStore store = new UpdateStateStore(context);
        if (!store.runIfScheduledDownload(id, () -> {})) return;
        WorkManager workManager;
        try {
            workManager = WorkManager.getInstance(context);
        } catch (IllegalStateException exception) {
            failIfCurrent(context, id);
            LogUtility.w("Update work is unavailable: " + exception.getClass().getSimpleName());
            return;
        }
        try {
            Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateDownloadWorker.class)
                .setId(id).setConstraints(constraints).build();
            workManager.enqueueUniqueWork(WORK_PREFIX + id, ExistingWorkPolicy.KEEP, request)
                .getResult().get(10L, TimeUnit.SECONDS);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            failIfCurrent(context, id);
            LogUtility.w("Unable to enqueue update work: " + exception.getClass().getSimpleName());
        }
    }

    private static void failIfCurrent(Context context, UUID id) {
        UpdateStateStore store = new UpdateStateStore(context);
        store.runIfScheduledDownload(id, () -> {
            UpdateState state = store.load();
            UpdateState error = UpdateState.error(state.getVersion(), state.getFilePath(), "interrupted_download", System.currentTimeMillis());
            store.save(error);
            UpdateNotification.publish(context, error);
        });
    }
}
