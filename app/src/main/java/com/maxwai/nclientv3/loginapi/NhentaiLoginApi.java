package com.maxwai.nclientv3.loginapi;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/** Login protocol for the official nhentai origin. Calls run off the main thread. */
public final class NhentaiLoginApi {
    private static final HttpUrl ORIGIN = HttpUrl.get("https://nhentai.net/");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long MAX_RESPONSE = 65536;

    private NhentaiLoginApi() {
    }

    public static final class ProtectionException extends IOException {
        ProtectionException() {
            super("The login request was blocked by site protection");
        }
    }

    private static OkHttpClient client(Context context) {
        return client(context, false);
    }

    static OkHttpClient client(Context context, boolean explicitProtectionCookies) {
        CookieJar existing = Global.getClient(context).cookieJar();
        CookieJar networkCookies = new CookieJar() {
            @Override public void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> cookies) {
            }

            @NonNull @Override public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
                if (!url.host().equals(ORIGIN.host()) || !url.isHttps() || url.port() != 443)
                    return java.util.Collections.emptyList();
                List<Cookie> accepted = new java.util.ArrayList<>();
                for (Cookie cookie : existing.loadForRequest(url)) {
                    if (cookie.name().equals("cf_clearance") || cookie.name().equals("__cf_bm"))
                        accepted.add(cookie);
                }
                return accepted;
            }
        };
        OkHttpClient.Builder builder = Global.getClient(context).newBuilder()
            .cookieJar(explicitProtectionCookies ? CookieJar.NO_COOKIES : networkCookies)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        return builder.build();
    }

    public static final class CancelHandle {
        private boolean cancelled;
        private Call call;

        synchronized Call attach(Call next) throws IOException {
            if (cancelled) {
                next.cancel();
                throw new IOException("Login cancelled");
            }
            call = next;
            return next;
        }

        synchronized void detach(Call previous) {
            if (call == previous) call = null;
        }

        public synchronized void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
        }
    }

    private static JSONObject execute(Context context, Request request, CancelHandle handle) throws IOException {
        Call call = handle.attach(client(context).newCall(request));
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                LogUtility.w("Authentication configuration request failed: HTTP " + response.code());
                throw new IOException("Authentication service rejected request");
            }
            BufferedSource source = response.body().source();
            source.request(MAX_RESPONSE + 1);
            if (source.getBuffer().size() > MAX_RESPONSE) throw new IOException("Authentication response too large");
            try {
                return new JSONObject(source.readUtf8());
            } catch (JSONException exception) {
                throw new IOException("Invalid authentication response", exception);
            }
        } finally { handle.detach(call); }
    }

    @NonNull
    public static String captchaSiteKey(@NonNull Context context, @NonNull CancelHandle handle) throws IOException {
        JSONObject body = execute(context, new Request.Builder().url(ORIGIN.resolve("/api/v2/captcha")).build(), handle);
        return parseCaptchaSiteKey(body);
    }

    static String parseCaptchaSiteKey(@NonNull JSONObject body) throws IOException {
        Object provider = body.opt("provider");
        Object key = body.opt("site_key");
        if (!(provider instanceof String) || !"turnstile".equalsIgnoreCase((String) provider)
            || !(key instanceof String) || !validSiteKey((String) key))
            throw new IOException("Unsupported CAPTCHA configuration");
        return (String) key;
    }

    static boolean validSiteKey(@NonNull String key) {
        return key.length() <= 256 && key.matches("[A-Za-z0-9_-]{1,256}");
    }

    public static final class Challenge {
        @NonNull public final String value;
        public final int difficulty;

        Challenge(String value, int difficulty) {
            this.value = value;
            this.difficulty = difficulty;
        }
    }

    @NonNull
    public static Challenge challenge(@NonNull Context context, @NonNull CancelHandle handle) throws IOException {
        JSONObject body = execute(context, new Request.Builder()
            .url(ORIGIN.resolve("/api/v2/pow?action=login")).build(), handle);
        return parseChallenge(body);
    }

    static Challenge parseChallenge(@NonNull JSONObject body) throws IOException {
        Object challenge = body.opt("challenge");
        Object difficulty = body.opt("difficulty");
        if (!(challenge instanceof String) || ((String) challenge).isEmpty()
            || ((String) challenge).length() > 4096 || !(difficulty instanceof Integer)
            || (Integer) difficulty < 0 || (Integer) difficulty > 256) throw new IOException("Invalid PoW challenge");
        return new Challenge((String) challenge, (Integer) difficulty);
    }

    public static void login(@NonNull Context context, @NonNull String username, @NonNull String password,
                             @NonNull String captcha, @NonNull Challenge challenge, @NonNull String nonce,
                             long attempt, @NonNull CancelHandle handle, @NonNull String userAgent,
                             @NonNull String protectionCookies)
        throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
            payload.put("captcha_response", captcha);
            payload.put("pow_challenge", challenge.value);
            payload.put("pow_nonce", nonce);
        } catch (JSONException exception) {
            throw new IOException("Unable to prepare login", exception);
        }
        Request.Builder builder = new Request.Builder().url(ORIGIN.resolve("/api/v2/auth/login"))
            .header("User-Agent", userAgent).header("Origin", "https://nhentai.net")
            .header("Referer", "https://nhentai.net/login")
            .post(RequestBody.create(payload.toString(), JSON));
        if (!protectionCookies.isEmpty()) builder.header("Cookie", protectionCookies);
        Request request = builder.build();
        Call call = handle.attach(client(context, !protectionCookies.isEmpty()).newCall(request));
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String type = response.header("Content-Type", "");
                LogUtility.w("Account sign-in request failed: HTTP " + response.code()
                    + (type.startsWith("application/json") ? " (JSON)" : " (non-JSON)"));
                if (type.startsWith("application/json")) {
                    try {
                        String error = new JSONObject(response.peekBody(8192).string()).optString("error", "");
                        if ("Invalid CAPTCHA solution".equalsIgnoreCase(error))
                            LogUtility.w("Account sign-in CAPTCHA was rejected");
                        else if ("Invalid proof of work".equalsIgnoreCase(error))
                            LogUtility.w("Account sign-in proof of work was rejected");
                    } catch (JSONException ignored) {
                        // Preserve the HTTP failure without logging the response body.
                    }
                }
                if (response.code() == 403 && !type.startsWith("application/json"))
                    throw new ProtectionException();
                throw new IOException("Login rejected");
            }
            BufferedSource source = response.body().source();
            source.request(MAX_RESPONSE + 1);
            if (source.getBuffer().size() > MAX_RESPONSE) throw new IOException("Login response too large");
            JSONObject body;
            try {
                body = new JSONObject(source.readUtf8());
            } catch (JSONException exception) {
                throw new IOException("Invalid login response", exception);
            }
            List<Cookie> cookies = parseTokens(body);
            if (!AuthSessionStore.completeAttempt(context, cookies, attempt)) throw new IOException("Login not completed");
        } finally { handle.detach(call); }
    }

    static List<Cookie> parseTokens(@NonNull JSONObject body) throws IOException {
        if (body.optJSONObject("user") == null || (body.has("success") && !Boolean.TRUE.equals(body.opt("success"))))
            throw new IOException("Login was not confirmed");
        Object access = body.opt("access_token");
        Object refresh = body.opt("refresh_token");
        if (!(access instanceof String) || !(refresh instanceof String))
            throw new IOException("Invalid login credentials response");
        return AuthSessionStore.tokenCookies((String) access, (String) refresh);
    }

}
