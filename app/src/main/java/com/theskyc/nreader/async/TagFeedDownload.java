package com.theskyc.nreader.async;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** An account-free downloader for tag data. Each instance runs one request at a time. */
final class TagFeedDownload {
    private static final class PublicClient {
        static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    static OkHttpClient publicClient() {
        return PublicClient.INSTANCE;
    }

    private final Call.Factory client;
    private final BooleanSupplier stopped;
    private volatile Call activeCall;

    TagFeedDownload(BooleanSupplier stopped) {
        this(request -> publicClient().newCall(request), stopped);
    }

    TagFeedDownload(Call.Factory client, BooleanSupplier stopped) {
        this.client = client;
        this.stopped = stopped;
    }

    void copy(HttpUrl url, OutputStream output, int limit, boolean requireIdentity) throws IOException {
        checkCancelled();
        Request.Builder request = new Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK);
        if (requireIdentity) request.header("Accept-Encoding", "identity");
        Call call = client.newCall(request.build());
        activeCall = call;
        try {
            checkCancelled();
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                if (response.code() != 200 || body == null) {
                    throw new IOException("Tag feed request failed with HTTP " + response.code());
                }
                List<String> encodings = response.headers("Content-Encoding");
                if (requireIdentity && !encodings.isEmpty()
                    && (encodings.size() != 1 || !"identity".equalsIgnoreCase(encodings.get(0)))) {
                    throw new IOException("Unexpected tag feed content encoding");
                }
                if (body.contentLength() > limit) throw new IOException("Tag feed response exceeds limit");
                byte[] buffer = new byte[8192];
                int total = 0;
                try (InputStream input = body.byteStream()) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        total += count;
                        if (total > limit) throw new IOException("Tag feed response exceeds limit");
                        output.write(buffer, 0, count);
                    }
                }
            }
            checkCancelled();
        } finally {
            activeCall = null;
        }
    }

    void cancelActiveCall() {
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    void checkCancelled() throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Tag feed request cancelled");
        }
    }
}
