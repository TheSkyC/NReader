// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

public enum DownloadFailure {
    NONE(false), NETWORK(true), AUTHENTICATION(false), RATE_LIMIT(true),
    SERVER(true), NOT_FOUND(false), STORAGE(false), INVALID_DATA(false);

    public final boolean retryable;

    DownloadFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public static DownloadFailure fromHttpCode(int code) {
        if (code == 401 || code == 403) return AUTHENTICATION;
        if (code == 429) return RATE_LIMIT;
        if (code == 408) return NETWORK;
        if (code >= 500) return SERVER;
        if (code == 404 || code == 410) return NOT_FOUND;
        return INVALID_DATA;
    }
}
