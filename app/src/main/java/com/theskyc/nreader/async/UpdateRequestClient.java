// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import androidx.annotation.NonNull;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Applies the update transport policy to metadata and manifest requests. */
final class UpdateRequestClient {
    private UpdateRequestClient() {}

    @NonNull
    static Call newCall(@NonNull OkHttpClient client, @NonNull Request request) {
        return clientForRequest(client, request).newCall(request);
    }

    @NonNull
    static OkHttpClient clientForRequest(@NonNull OkHttpClient client, @NonNull Request request) {
        OkHttpClient.Builder builder = client.newBuilder().followSslRedirects(false);
        if (!request.url().isHttps()) builder.followRedirects(false);
        return builder.build();
    }
}
