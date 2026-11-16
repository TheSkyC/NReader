// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import androidx.annotation.NonNull;

import java.util.UUID;

/** Immutable identity and asset details for one update download attempt. */
public final class UpdateDownloadRecord {
    private final UUID id;
    private final String version;
    private final String filePath;
    private final String url;
    private final String sha256;
    private final long size;

    public UpdateDownloadRecord(@NonNull UUID id, @NonNull String version, @NonNull String filePath,
                                @NonNull String url, @NonNull String sha256, long size) {
        this.id = id;
        this.version = version;
        this.filePath = filePath;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
    }

    @NonNull public UUID getId() { return id; }
    @NonNull public String getVersion() { return version; }
    @NonNull public String getFilePath() { return filePath; }
    @NonNull public String getUrl() { return url; }
    @NonNull public String getSha256() { return sha256; }
    public long getSize() { return size; }
}
