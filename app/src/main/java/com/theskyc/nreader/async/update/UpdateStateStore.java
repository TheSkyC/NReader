// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

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
    private static final String TASK_ID = "task_id";
    private static final String TASK_URL = "task_url";
    private static final String TASK_SHA256 = "task_sha256";
    private static final String TASK_SIZE = "task_size";
    private static final String TASK_BACKEND = "task_backend";
    private static final String CANCEL_ID = "cancel_id";
    private static final String CANCEL_URL = "cancel_url";
    private static final String CANCEL_SHA256 = "cancel_sha256";
    private static final String CANCEL_PATH = "cancel_path";
    private static final String CANCEL_VERSION = "cancel_version";
    private static final String CANCEL_SIZE = "cancel_size";
    private static final String CANCEL_PREFIX = "canceled_task_";
    private static final String WORK_MANAGER = "work_manager";
    // All update components run in the default app process; the revision only
    // guards concurrent work while that process is alive.
    private static final Object LOCK = new Object();
    private static long revision;

    private final SharedPreferences preferences;

    public UpdateStateStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public void registerListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
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
            UpdateState previous = load();
            boolean sameDownload = isDownloadPhase(previous.getPhase())
                && isDownloadPhase(state.getPhase())
                && Objects.equals(previous.getVersion(), state.getVersion())
                && Objects.equals(previous.getFilePath(), state.getFilePath());
            SharedPreferences.Editor editor = stateEditor(state);
            if (!sameDownload) clearDownloadRecord(editor);
            commit(editor);
        }
    }

    public boolean claimDownload(@NonNull UpdateDownloadRecord record, long now) {
        return claimDownload(record, now, false);
    }

    public boolean claimScheduledDownload(@NonNull UpdateDownloadRecord record, long now) {
        return claimDownload(record, now, true);
    }

    public void recordCancellation(@NonNull UpdateDownloadRecord record) {
        synchronized (LOCK) {
            try {
                JSONObject data = new JSONObject()
                    .put("version", record.getVersion()).put("path", record.getFilePath())
                    .put("url", record.getUrl()).put("sha256", record.getSha256())
                    .put("size", record.getSize());
                commit(preferences.edit().putString(CANCEL_PREFIX + record.getId(), data.toString()));
            } catch (JSONException exception) {
                throw new IllegalStateException("Unable to serialize canceled update", exception);
            }
        }
    }

    @NonNull
    public List<UpdateDownloadRecord> loadCancellations() {
        synchronized (LOCK) {
            List<UpdateDownloadRecord> records = new ArrayList<>();
            UpdateDownloadRecord legacy = loadCancellation();
            if (legacy != null) records.add(legacy);
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(CANCEL_PREFIX) || !(entry.getValue() instanceof String)) continue;
                try {
                    String id = entry.getKey().substring(CANCEL_PREFIX.length());
                    UUID uuid = UUID.fromString(id);
                    if (!uuid.toString().equals(id)) continue;
                    JSONObject data = new JSONObject((String) entry.getValue());
                    records.add(new UpdateDownloadRecord(uuid, data.getString("version"), data.getString("path"),
                        data.getString("url"), data.getString("sha256"), data.getLong("size")));
                } catch (IllegalArgumentException | JSONException exception) {
                    // Retain malformed records without using them for file operations.
                }
            }
            return records;
        }
    }

    @Nullable
    public UpdateDownloadRecord loadCancellation() {
        synchronized (LOCK) {
            try {
                String id = preferences.getString(CANCEL_ID, null);
                String version = preferences.getString(CANCEL_VERSION, null);
                String path = preferences.getString(CANCEL_PATH, null);
                String url = preferences.getString(CANCEL_URL, null);
                String sha256 = preferences.getString(CANCEL_SHA256, null);
                if (id == null || version == null || path == null || url == null || sha256 == null
                    || !preferences.contains(CANCEL_SIZE)) return null;
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equals(id)) return null;
                return new UpdateDownloadRecord(uuid, version, path, url, sha256,
                    preferences.getLong(CANCEL_SIZE, -1L));
            } catch (IllegalArgumentException | ClassCastException exception) {
                return null;
            }
        }
    }

    public void clearCancellation() {
        synchronized (LOCK) {
            commit(preferences.edit().remove(CANCEL_ID).remove(CANCEL_VERSION).remove(CANCEL_PATH)
                .remove(CANCEL_URL).remove(CANCEL_SHA256).remove(CANCEL_SIZE));
        }
    }

    public void clearCancellationIf(@NonNull UUID id) {
        synchronized (LOCK) {
            try {
                String storedId = preferences.getString(CANCEL_ID, null);
                SharedPreferences.Editor editor = preferences.edit().remove(CANCEL_PREFIX + id);
                if (id.toString().equals(storedId)) {
                    editor.remove(CANCEL_ID).remove(CANCEL_VERSION).remove(CANCEL_PATH)
                        .remove(CANCEL_URL).remove(CANCEL_SHA256).remove(CANCEL_SIZE);
                }
                commit(editor);
            } catch (ClassCastException ignored) {
                // Preserve malformed cancellation evidence for startup recovery.
            }
        }
    }

    private boolean claimDownload(@NonNull UpdateDownloadRecord record, long now, boolean scheduled) {
        synchronized (LOCK) {
            UpdateState current = load();
            if ((current.getPhase() != UpdatePhase.AVAILABLE && current.getPhase() != UpdatePhase.ERROR)
                || !record.getVersion().equals(current.getVersion())) return false;
            UpdateState state = scheduled
                ? UpdateState.queued(record.getVersion(), record.getFilePath(), record.getSize(), now)
                : UpdateState.downloading(record.getVersion(), record.getFilePath(), 0L, record.getSize(), now);
            SharedPreferences.Editor editor = stateEditor(state)
                .putString(TASK_ID, record.getId().toString())
                .putString(TASK_URL, record.getUrl())
                .putString(TASK_SHA256, record.getSha256())
                .putLong(TASK_SIZE, record.getSize());
            if (scheduled) editor.putString(TASK_BACKEND, WORK_MANAGER);
            else editor.remove(TASK_BACKEND);
            commit(editor);
            return true;
        }
    }

    @Nullable
    public UpdateDownloadRecord loadDownloadRecord() {
        synchronized (LOCK) {
            UpdateState state = load();
            if (!isDownloadPhase(state.getPhase()) || state.getVersion() == null || state.getFilePath() == null) return null;
            try {
                String id = preferences.getString(TASK_ID, null);
                String url = preferences.getString(TASK_URL, null);
                String sha256 = preferences.getString(TASK_SHA256, null);
                if (id == null || url == null || sha256 == null || !preferences.contains(TASK_SIZE)) return null;
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equals(id)) return null;
                return new UpdateDownloadRecord(uuid, state.getVersion(), state.getFilePath(), url, sha256,
                    preferences.getLong(TASK_SIZE, -1L));
            } catch (IllegalArgumentException | ClassCastException exception) {
                return null;
            }
        }
    }

    public boolean isScheduledDownload() {
        synchronized (LOCK) {
            try {
                return isDownloadPhase(load().getPhase()) && WORK_MANAGER.equals(preferences.getString(TASK_BACKEND, null));
            } catch (ClassCastException exception) {
                return false;
            }
        }
    }

    public boolean runIfScheduledDownload(@NonNull UUID id, @NonNull Runnable action) {
        synchronized (LOCK) {
            UpdateDownloadRecord record = loadDownloadRecord();
            if (!isScheduledDownload() || record == null || !id.equals(record.getId())) return false;
            action.run();
            return true;
        }
    }

    private static boolean isDownloadPhase(UpdatePhase phase) {
        return phase == UpdatePhase.QUEUED || phase == UpdatePhase.DOWNLOADING;
    }

    private SharedPreferences.Editor stateEditor(UpdateState state) {
        SharedPreferences.Editor editor = preferences.edit()
            .putString(PHASE, state.getPhase().name())
            .putLong(DOWNLOADED_BYTES, state.getDownloadedBytes())
            .putLong(TOTAL_BYTES, state.getTotalBytes())
            .putLong(UPDATED_AT, state.getUpdatedAt());
        putNullable(editor, VERSION, state.getVersion());
        putNullable(editor, FILE_PATH, state.getFilePath());
        putNullable(editor, ERROR_CODE, state.getErrorCode());
        return editor;
    }

    private static void clearDownloadRecord(SharedPreferences.Editor editor) {
        editor.remove(TASK_ID).remove(TASK_URL).remove(TASK_SHA256).remove(TASK_SIZE).remove(TASK_BACKEND);
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Unable to persist update state");
        revision++;
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
            case QUEUED:
                return version == null || filePath == null
                    ? UpdateState.idle(updatedAt) : UpdateState.queued(version, filePath, totalBytes, updatedAt);
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
