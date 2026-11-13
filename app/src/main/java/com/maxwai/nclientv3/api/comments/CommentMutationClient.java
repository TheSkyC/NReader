package com.maxwai.nclientv3.api.comments;

import android.content.Context;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.Global;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

public final class CommentMutationClient {
    private static final String ORIGIN = "https://nhentai.net/api/v2/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long MAX_RESPONSE = 65536;

    public static final class Result {
        public final int status;
        @Nullable public final Comment comment;
        public final boolean confirmed;

        Result(int status, @Nullable Comment comment, boolean confirmed) {
            this.status = status;
            this.comment = comment;
            this.confirmed = confirmed;
        }
    }

    private final Context context;
    private final String accountId;
    private final long revision;
    private final BooleanSupplier current;
    @Nullable private Call call;
    private boolean cancelled;

    public CommentMutationClient(@NonNull Context context, @NonNull String accountId, long revision,
                                 @NonNull BooleanSupplier current) {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
        this.revision = revision;
        this.current = current;
    }

    public synchronized void cancel() {
        cancelled = true;
        if (call != null) call.cancel();
    }

    public Result publish(int galleryId, @NonNull String text, @NonNull String captcha,
                          @NonNull String challenge, @NonNull String nonce,
                          @NonNull String userAgent, @NonNull String protectionCookies) throws IOException {
        if (galleryId <= 0 || text.codePointCount(0, text.length()) < 10
            || text.codePointCount(0, text.length()) > 1000 || text.trim().isEmpty())
            throw new IllegalArgumentException("Invalid comment");
        try {
            JSONObject body = new JSONObject().put("body", text).put("captcha_response", captcha)
                .put("pow_challenge", challenge).put("pow_nonce", nonce);
            Request.Builder request = base(ORIGIN + "galleries/" + galleryId + "/comments")
                .header("User-Agent", userAgent).header("Origin", "https://nhentai.net")
                .header("Referer", "https://nhentai.net/g/" + galleryId + "/")
                .post(RequestBody.create(body.toString(), JSON));
            if (!protectionCookies.isEmpty()) {
                if (!protectionCookies.matches("(?:cf_clearance|__cf_bm)=[A-Za-z0-9._~+%/=-]+(?:; (?:cf_clearance|__cf_bm)=[A-Za-z0-9._~+%/=-]+)?"))
                    throw new IOException("Invalid protection cookies");
                request.header("Cookie", protectionCookies);
            }
            return execute(request.build(), galleryId);
        } catch (JSONException exception) {
            throw new IOException("Unable to prepare comment", exception);
        }
    }

    public Result delete(int commentId) throws IOException {
        if (commentId <= 0) throw new IllegalArgumentException("Invalid comment ID");
        return execute(base(ORIGIN + "comments/" + commentId).delete().build(), 0);
    }

    private Request.Builder base(String url) {
        return new Request.Builder().url(url).tag(ApiAuthInterceptor.AccountIdentity.class,
            new ApiAuthInterceptor.AccountIdentity(accountId, revision, current));
    }

    private Result execute(Request request, int galleryId) throws IOException {
        Call active = clientFor(Global.getClient(context), request).newCall(request);
        synchronized (this) {
            if (cancelled) throw new IOException("Comment operation cancelled");
            call = active;
        }
        try (Response response = active.execute()) {
            if (!response.isSuccessful()) return new Result(response.code(), null, false);
            if (response.body() == null || response.body().contentLength() > MAX_RESPONSE)
                throw new IOException("Invalid comment response");
            BufferedSource source = response.body().source();
            source.request(MAX_RESPONSE + 1);
            if (source.getBuffer().size() > MAX_RESPONSE) throw new IOException("Comment response too large");
            String body = source.readUtf8();
            try {
                JSONObject object = new JSONObject(body);
                if (galleryId == 0) return new Result(response.code(), null, object.optBoolean("success", false));
                if (object.optInt("gallery_id", -1) != galleryId) throw new IOException("Unexpected gallery");
                try (JsonReader reader = new JsonReader(new StringReader(body))) {
                    return new Result(response.code(), new Comment(reader), true);
                }
            } catch (JSONException exception) {
                throw new IOException("Invalid comment response", exception);
            }
        } finally {
            synchronized (this) { if (call == active) call = null; }
        }
    }

    static okhttp3.OkHttpClient clientFor(okhttp3.OkHttpClient base, Request request) {
        okhttp3.OkHttpClient.Builder builder = base.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS);
        if (request.header("Cookie") != null) builder.cookieJar(CookieJar.NO_COOKIES);
        return builder.build();
    }
}
