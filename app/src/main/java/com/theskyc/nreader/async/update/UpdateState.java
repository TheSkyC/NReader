// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Immutable update task snapshot. It contains no Android or network behavior. */
public final class UpdateState {
    private final UpdatePhase phase;
    private final String version;
    private final String filePath;
    private final long downloadedBytes;
    private final long totalBytes;
    private final String errorCode;
    private final long updatedAt;

    private UpdateState(
        @NonNull UpdatePhase phase,
        @Nullable String version,
        @Nullable String filePath,
        long downloadedBytes,
        long totalBytes,
        @Nullable String errorCode,
        long updatedAt
    ) {
        this.phase = phase;
        this.version = version;
        this.filePath = filePath;
        this.downloadedBytes = Math.max(0L, downloadedBytes);
        this.totalBytes = Math.max(0L, totalBytes);
        this.errorCode = errorCode;
        this.updatedAt = Math.max(0L, updatedAt);
    }

    public static UpdateState idle(long now) {
        return new UpdateState(UpdatePhase.IDLE, null, null, 0L, 0L, null, now);
    }

    public static UpdateState checking(long now) {
        return new UpdateState(UpdatePhase.CHECKING, null, null, 0L, 0L, null, now);
    }

    public static UpdateState available(@NonNull String version, long now) {
        return new UpdateState(UpdatePhase.AVAILABLE, version, null, 0L, 0L, null, now);
    }

    public static UpdateState downloading(
        @NonNull String version,
        @NonNull String filePath,
        long downloadedBytes,
        long totalBytes,
        long now
    ) {
        return new UpdateState(
            UpdatePhase.DOWNLOADING,
            version,
            filePath,
            downloadedBytes,
            totalBytes,
            null,
            now
        );
    }

    public static UpdateState queued(@NonNull String version, @NonNull String filePath, long totalBytes, long now) {
        return new UpdateState(UpdatePhase.QUEUED, version, filePath, 0L, totalBytes, null, now);
    }

    public static UpdateState readyToInstall(
        @NonNull String version,
        @NonNull String filePath,
        long totalBytes,
        long now
    ) {
        return new UpdateState(
            UpdatePhase.READY_TO_INSTALL,
            version,
            filePath,
            totalBytes,
            totalBytes,
            null,
            now
        );
    }

    public static UpdateState installing(
        @NonNull String version,
        @NonNull String filePath,
        long now
    ) {
        return new UpdateState(UpdatePhase.INSTALLING, version, filePath, 0L, 0L, null, now);
    }

    public static UpdateState error(
        @Nullable String version,
        @Nullable String filePath,
        @NonNull String errorCode,
        long now
    ) {
        return new UpdateState(UpdatePhase.ERROR, version, filePath, 0L, 0L, errorCode, now);
    }

    @NonNull public UpdatePhase getPhase() { return phase; }
    @Nullable public String getVersion() { return version; }
    @Nullable public String getFilePath() { return filePath; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public long getTotalBytes() { return totalBytes; }
    @Nullable public String getErrorCode() { return errorCode; }
    public long getUpdatedAt() { return updatedAt; }

    public boolean isReadyToInstall() {
        return phase == UpdatePhase.READY_TO_INSTALL && version != null && filePath != null;
    }

    public boolean hasKnownProgress() {
        return totalBytes > 0L && downloadedBytes <= totalBytes;
    }
}
