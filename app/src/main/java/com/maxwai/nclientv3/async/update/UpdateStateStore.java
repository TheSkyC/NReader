// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Small durable store for update task recovery. */
public final class UpdateStateStore {
    private static final String PREFERENCES = "UpdateState";
    private static final String PHASE = "phase";
    private static final String VERSION = "version";
    private static final String FILE_PATH = "file_path";
    private static final String DOWNLOADED_BYTES = "downloaded_bytes";
    private static final String TOTAL_BYTES = "total_bytes";
    private static final String ERROR_CODE = "error_code";
    private static final String UPDATED_AT = "updated_at";
    // All update components run in the default app process; the revision only
    // guards concurrent work while that process is alive.
    private static final Object LOCK = new Object();
    private static long revision;

    private final SharedPreferences preferences;

    public UpdateStateStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @NonNull
    public UpdateState load() {
        synchronized (LOCK) {
            String phaseName = preferences.getString(PHASE, UpdatePhase.IDLE.name());
            UpdatePhase phase;
            try {
                phase = UpdatePhase.valueOf(phaseName);
            } catch (IllegalArgumentException ignored) {
                phase = UpdatePhase.IDLE;
            }
            String version = preferences.getString(VERSION, null);
            String filePath = preferences.getString(FILE_PATH, null);
            String errorCode = preferences.getString(ERROR_CODE, null);
            long downloadedBytes = preferences.getLong(DOWNLOADED_BYTES, 0L);
            long totalBytes = preferences.getLong(TOTAL_BYTES, 0L);
            long updatedAt = preferences.getLong(UPDATED_AT, 0L);
            return newState(phase, version, filePath, downloadedBytes, totalBytes, errorCode, updatedAt);
        }
    }

    public void save(@NonNull UpdateState state) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit()
                .putString(PHASE, state.getPhase().name())
                .putLong(DOWNLOADED_BYTES, state.getDownloadedBytes())
                .putLong(TOTAL_BYTES, state.getTotalBytes())
                .putLong(UPDATED_AT, state.getUpdatedAt());
            putNullable(editor, VERSION, state.getVersion());
            putNullable(editor, FILE_PATH, state.getFilePath());
            putNullable(editor, ERROR_CODE, state.getErrorCode());
            if (!editor.commit()) {
                throw new IllegalStateException("Unable to persist update state");
            }
            revision++;
        }
    }

    public void clear() {
        synchronized (LOCK) {
            if (!preferences.edit().clear().commit()) {
                throw new IllegalStateException("Unable to clear update state");
            }
            revision++;
        }
    }

    public long revision() {
        synchronized (LOCK) {
            return revision;
        }
    }

    public boolean runIfCheckableAndUnchanged(long expectedRevision, @NonNull Runnable action) {
        synchronized (LOCK) {
            UpdatePhase phase = load().getPhase();
            if (revision != expectedRevision || (phase != UpdatePhase.IDLE && phase != UpdatePhase.AVAILABLE)) return false;
            action.run();
            return true;
        }
    }

    public boolean runIfDownloading(@NonNull String filePath, @NonNull Runnable action) {
        synchronized (LOCK) {
            UpdateState state = load();
            if (state.getPhase() != UpdatePhase.DOWNLOADING || !filePath.equals(state.getFilePath())) return false;
            action.run();
            return true;
        }
    }

    public boolean runIfUnchanged(long expectedRevision, @NonNull Runnable action) {
        synchronized (LOCK) {
            if (revision != expectedRevision) return false;
            action.run();
            return true;
        }
    }

    public boolean runIfDownloadable(@NonNull String version, @NonNull Runnable action) {
        synchronized (LOCK) {
            UpdateState state = load();
            if ((state.getPhase() != UpdatePhase.AVAILABLE && state.getPhase() != UpdatePhase.ERROR)
                || !version.equals(state.getVersion())) return false;
            action.run();
            return true;
        }
    }

    private static void putNullable(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) editor.remove(key);
        else editor.putString(key, value);
    }

    private static UpdateState newState(
        UpdatePhase phase,
        String version,
        String filePath,
        long downloadedBytes,
        long totalBytes,
        String errorCode,
        long updatedAt
    ) {
        switch (phase) {
            case CHECKING:
                return UpdateState.checking(updatedAt);
            case AVAILABLE:
                return version == null ? UpdateState.idle(updatedAt) : UpdateState.available(version, updatedAt);
            case DOWNLOADING:
                return version == null || filePath == null
                    ? UpdateState.idle(updatedAt)
                    : UpdateState.downloading(version, filePath, downloadedBytes, totalBytes, updatedAt);
            case READY_TO_INSTALL:
                return version == null || filePath == null
                    ? UpdateState.idle(updatedAt)
                    : UpdateState.readyToInstall(version, filePath, totalBytes, updatedAt);
            case INSTALLING:
                return version == null || filePath == null
                    ? UpdateState.idle(updatedAt)
                    : UpdateState.installing(version, filePath, updatedAt);
            case ERROR:
                return UpdateState.error(version, filePath, errorCode == null ? "unknown" : errorCode, updatedAt);
            case IDLE:
            default:
                return UpdateState.idle(updatedAt);
        }
    }
}
