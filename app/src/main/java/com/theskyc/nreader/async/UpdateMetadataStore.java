// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

/** Conditional-request cache for release metadata, separate from task state. */
public final class UpdateMetadataStore {
    private static final String PREFERENCES = "UpdateMetadata";
    private static final String BODY = "body";
    private static final String ETAG = "etag";
    private static final String FEED_BODY = "feed_body";
    private static final String FEED_ETAG = "feed_etag";
    private static final String FEED_URL = "feed_url";
    private static final String SELECTED_SHA256 = "selected_sha256";
    private static final String SELECTED_SIZE = "selected_size";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_SUCCESS = "last_success";
    private static final String SELECTED_VERSION = "selected_version";
    private static final String SELECTED_VERSION_CODE = "selected_version_code";
    private static final String SELECTED_URL = "selected_url";
    private static final String SELECTED_BODY = "selected_body";
    private static final String SELECTED_BETA = "selected_beta";

    private final SharedPreferences preferences;

    public UpdateMetadataStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Nullable public String getBody() { return preferences.getString(BODY, null); }
    @Nullable public String getEtag() { return preferences.getString(ETAG, null); }
    @NonNull CacheSnapshot cacheSnapshot() {
        Map<String, ?> values = preferences.getAll();
        Object body = values.get(BODY);
        Object etag = values.get(ETAG);
        return new CacheSnapshot(body instanceof String ? (String) body : null, etag instanceof String ? (String) etag : null);
    }

    static final class CacheSnapshot {
        @Nullable final String body;
        @Nullable final String etag;

        CacheSnapshot(@Nullable String body, @Nullable String etag) {
            this.body = body;
            this.etag = etag;
        }
    }

    @NonNull CacheSnapshot feedSnapshot(@NonNull String url) {
        Map<String, ?> values = preferences.getAll();
        if (!url.equals(values.get(FEED_URL))) return new CacheSnapshot(null, null);
        Object body = values.get(FEED_BODY);
        Object etag = values.get(FEED_ETAG);
        return new CacheSnapshot(body instanceof String ? (String) body : null, etag instanceof String ? (String) etag : null);
    }

    void saveFeed(@NonNull String url, @NonNull String body, @Nullable String etag) {
        SharedPreferences.Editor editor = preferences.edit().putString(FEED_URL, url).putString(FEED_BODY, body);
        if (etag == null) editor.remove(FEED_ETAG);
        else editor.putString(FEED_ETAG, etag);
        if (!editor.commit()) throw new IllegalStateException("Unable to persist update feed");
    }
    @Nullable public String getSelectedSha256() { return preferences.getString(SELECTED_SHA256, null); }
    @Nullable public String getSelectedVersion() { return preferences.getString(SELECTED_VERSION, null); }
    @Nullable public String getSelectedUrl() { return preferences.getString(SELECTED_URL, null); }
    @Nullable public String getSelectedBody() { return preferences.getString(SELECTED_BODY, null); }
    public long getSelectedVersionCode() { return preferences.getLong(SELECTED_VERSION_CODE, -1L); }
    public boolean isSelectedBeta() { return preferences.getBoolean(SELECTED_BETA, false); }
    public long getSelectedSize() { return preferences.getLong(SELECTED_SIZE, -1L); }
    public long getLastAttempt() { return preferences.getLong(LAST_ATTEMPT, 0L); }
    public long getLastSuccessfulCheck() { return preferences.getLong(LAST_SUCCESS, 0L); }

    public void setSelectedAsset(@Nullable String sha256, long sizeBytes) {
        SharedPreferences.Editor editor = preferences.edit();
        if (sha256 == null) editor.remove(SELECTED_SHA256);
        else editor.putString(SELECTED_SHA256, sha256);
        if (sizeBytes > 0L) editor.putLong(SELECTED_SIZE, sizeBytes);
        else editor.remove(SELECTED_SIZE);
        editor.apply();
    }

    public void setSelectedSha256(@Nullable String sha256) {
        setSelectedAsset(sha256, -1L);
    }

    public void saveSelectedRelease(
        @NonNull String version,
        long versionCode,
        @NonNull String url,
        @Nullable String body,
        boolean beta,
        @Nullable String sha256,
        long sizeBytes
    ) {
        SharedPreferences.Editor editor = preferences.edit()
            .putString(SELECTED_VERSION, version)
            .putLong(SELECTED_VERSION_CODE, versionCode)
            .putString(SELECTED_URL, url)
            .putBoolean(SELECTED_BETA, beta);
        if (body == null) editor.remove(SELECTED_BODY);
        else editor.putString(SELECTED_BODY, body);
        if (sha256 == null) editor.remove(SELECTED_SHA256);
        else editor.putString(SELECTED_SHA256, sha256);
        if (sizeBytes > 0L) editor.putLong(SELECTED_SIZE, sizeBytes);
        else editor.remove(SELECTED_SIZE);
        editor.apply();
    }

    public void clearSelectedRelease() {
        preferences.edit()
            .remove(SELECTED_VERSION)
            .remove(SELECTED_VERSION_CODE)
            .remove(SELECTED_URL)
            .remove(SELECTED_BODY)
            .remove(SELECTED_BETA)
            .remove(SELECTED_SHA256)
            .remove(SELECTED_SIZE)
            .apply();
    }

    public void markAttempt(long timestamp) {
        preferences.edit().putLong(LAST_ATTEMPT, Math.max(0L, timestamp)).apply();
    }

    public void markSuccessfulCheck(long timestamp) {
        preferences.edit().putLong(LAST_SUCCESS, Math.max(0L, timestamp)).apply();
    }

    public void save(@NonNull String body, @Nullable String etag) {
        SharedPreferences.Editor editor = preferences.edit().putString(BODY, body);
        if (etag == null) editor.remove(ETAG);
        else editor.putString(ETAG, etag);
        if (!editor.commit()) throw new IllegalStateException("Unable to persist update metadata");
    }
}
