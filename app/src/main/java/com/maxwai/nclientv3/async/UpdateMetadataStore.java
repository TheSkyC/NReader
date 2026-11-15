// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Conditional-request cache for release metadata, separate from task state. */
final class UpdateMetadataStore {
    private static final String PREFERENCES = "UpdateMetadata";
    private static final String BODY = "body";
    private static final String ETAG = "etag";
    private static final String SELECTED_SHA256 = "selected_sha256";

    private final SharedPreferences preferences;

    UpdateMetadataStore(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Nullable String getBody() { return preferences.getString(BODY, null); }
    @Nullable String getEtag() { return preferences.getString(ETAG, null); }
    @Nullable String getSelectedSha256() { return preferences.getString(SELECTED_SHA256, null); }

    void setSelectedSha256(@Nullable String sha256) {
        SharedPreferences.Editor editor = preferences.edit();
        if (sha256 == null) editor.remove(SELECTED_SHA256);
        else editor.putString(SELECTED_SHA256, sha256);
        editor.apply();
    }

    void save(@NonNull String body, @Nullable String etag) {
        SharedPreferences.Editor editor = preferences.edit().putString(BODY, body);
        if (etag == null) editor.remove(ETAG);
        else editor.putString(ETAG, etag);
        if (!editor.commit()) throw new IllegalStateException("Unable to persist update metadata");
    }
}
