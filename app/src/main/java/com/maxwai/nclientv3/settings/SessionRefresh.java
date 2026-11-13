package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;

final class SessionRefresh {
    private static final HttpUrl ORIGIN = HttpUrl.get("https://nhentai.net/");
    private static long lastFailure;
    private static String failedIdentity;

    private SessionRefresh() {
    }

    @Nullable
    static synchronized String accessAccount(@NonNull Context context, @NonNull String accountId, long revision) {
        AccountRepository repository = AccountRuntime.repository();
        if (repository == null) return null;
        AccountVault.Account account;
        synchronized (repository) {
            account = repository.account(accountId);
            if (account == null || repository.accountRevision(accountId) != revision
                || account.type != AccountVault.Type.PASSWORD || account.rejected
                || account.refreshToken == null || account.refreshExpiry <= System.currentTimeMillis()) return null;
            if (account.accessExpiry > System.currentTimeMillis() && decode(account.accessToken) != null)
                return decode(account.accessToken);
        }
        String refresh = account.refreshToken;
        if (accountId.equals(failedIdentity) && lastFailure != 0
            && SystemClock.elapsedRealtime() - lastFailure < 30000) return null;
        try {
            JSONObject payload = new JSONObject().put("refresh_token", refresh);
            Request request = new Request.Builder().url(ORIGIN.resolve("/api/v2/auth/refresh"))
                .tag(ApiAuthInterceptor.RefreshRequest.class, new ApiAuthInterceptor.RefreshRequest())
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"))).build();
            try (Response response = Global.getClient(context).newBuilder().cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute()) {
                if (response.code() == 401) {
                    repository.rejectAccountSession(accountId, revision, refresh);
                    return null;
                }
                if (!response.isSuccessful() || response.body() == null
                    || response.body().contentLength() > 65536) throw new IOException("Refresh failed");
                okio.BufferedSource source = response.body().source();
                source.request(65537);
                if (source.getBuffer().size() > 65536) throw new IOException("Refresh response too large");
                JSONObject body = new JSONObject(source.readUtf8());
                Object access = body.opt("access_token");
                Object replacement = body.opt("refresh_token");
                if (!(access instanceof String) || !(replacement instanceof String)) throw new IOException("Invalid refresh response");
                java.util.List<okhttp3.Cookie> cookies = AuthSessionStore.tokenCookies((String) access, (String) replacement);
                if (cookies.size() != 2 || !repository.updateAccountSession(accountId, revision, refresh,
                    cookies.get(0).value(), cookies.get(1).value(), cookies.get(0).expiresAt(), cookies.get(1).expiresAt()))
                    throw new IOException("Refresh was not confirmed");
                lastFailure = 0;
                failedIdentity = null;
                return decode((String) access);
            }
        } catch (IOException | JSONException exception) {
            lastFailure = SystemClock.elapsedRealtime();
            failedIdentity = accountId;
            return null;
        }
    }

    @Nullable
    static synchronized String access(@NonNull Context context, @NonNull String identity, @Nullable String rejectedToken) {
        long generation = AuthStore.identityGeneration();
        if (generation < 0) return null;
        if (!identity.equals(AuthSessionStore.identity(context))) return null;
        String current = AuthSessionStore.accessTokenIfCurrent(context, identity);
        String decoded = decode(current);
        if (decoded != null && (rejectedToken == null || !decoded.equals(rejectedToken))) return decoded;
        if (current != null && rejectedToken != null && rejectedToken.equals(decoded))
            AuthSessionStore.invalidateAccessIfCurrent(context, identity, current, generation);
        if (identity.equals(failedIdentity) && SystemClock.elapsedRealtime() - lastFailure < 30000
            && lastFailure != 0) return null;
        String refresh = AuthSessionStore.refreshTokenIfCurrent(context, identity);
        if (refresh == null) return null;
        JSONObject payload = new JSONObject();
        try {
            payload.put("refresh_token", refresh);
        } catch (JSONException exception) {
            return null;
        }
        Request request = new Request.Builder().url(ORIGIN.resolve("/api/v2/auth/refresh"))
            .tag(ApiAuthInterceptor.RefreshRequest.class, new ApiAuthInterceptor.RefreshRequest())
            .post(RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        try (Response response = Global.getClient(context).newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute()) {
            if (response.code() == 401) {
                AuthSessionStore.rejectIfCurrent(context, identity, generation);
                return null;
            }
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Refresh failed");
            if (response.body().contentLength() > 65536) throw new IOException("Refresh response too large");
            okio.BufferedSource source = response.body().source();
            source.request(65537);
            if (source.getBuffer().size() > 65536) throw new IOException("Refresh response too large");
            JSONObject body = new JSONObject(source.readUtf8());
            Object access = body.opt("access_token");
            Object replacementRefresh = body.opt("refresh_token");
            if (!(access instanceof String) || !(replacementRefresh instanceof String)
                || !AuthSessionStore.replaceIfCurrent(context, AuthSessionStore.tokenCookies(
                    (String) access, (String) replacementRefresh), identity, generation, refresh))
                throw new IOException("Refresh was not confirmed");
            lastFailure = 0;
            failedIdentity = null;
            return decode(AuthSessionStore.accessTokenIfCurrent(context, identity));
        } catch (IOException | JSONException exception) {
            lastFailure = SystemClock.elapsedRealtime();
            failedIdentity = identity;
            return null;
        }
    }

    @Nullable
    private static String decode(@Nullable String value) {
        if (value == null) return null;
        try {
            String decoded = value;
            if (decoded.isEmpty() || decoded.length() > 8192) return null;
            for (int index = 0; index < decoded.length(); index++) {
                char character = decoded.charAt(index);
                if (character <= 0x20 || character >= 0x7f) return null;
            }
            return decoded;
        } catch (Exception exception) {
            return null;
        }
    }
}
