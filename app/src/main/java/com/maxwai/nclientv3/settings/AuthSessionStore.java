package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.UUID;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

/** Session cookies share the existing private authentication preferences and backup exclusions. */
public final class AuthSessionStore {
    private static final String ID = "session_identity";
    private static final String ACCESS = "session_access";
    private static final String REFRESH = "session_refresh";
    private static final String ACCESS_EXPIRY = "session_access_expiry";
    private static final String REFRESH_EXPIRY = "session_refresh_expiry";
    private static final String REJECTED = "session_rejected";
    private static final HttpUrl ORIGIN = HttpUrl.get("https://nhentai.net/");
    private static long attempt;

    private AuthSessionStore() {
    }

    public static boolean hasStoredSession(@NonNull Context context) {
        return AuthStore.getPreferences(context).contains(ID);
    }

    public static synchronized long beginAttempt() {
        return ++attempt;
    }

    public static synchronized void cancelAttempt(long id) {
        if (attempt == id) attempt++;
    }

    public static synchronized boolean completeAttempt(@NonNull Context context, @NonNull List<Cookie> cookies,
                                                       long id) {
        if (id != attempt) return false;
        boolean saved = replace(context, cookies, null);
        attempt++;
        return saved;
    }

    public static List<Cookie> tokenCookies(@NonNull String access, @NonNull String refresh) {
        if (!validToken(access) || !validToken(refresh)) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        return java.util.Arrays.asList(
            new Cookie.Builder().name("access_token").value(access).hostOnlyDomain(ORIGIN.host())
                .path("/").secure().expiresAt(now + 60L * 60L * 1000L).build(),
            new Cookie.Builder().name("refresh_token").value(refresh).hostOnlyDomain(ORIGIN.host())
                .path("/").secure().httpOnly().expiresAt(Long.MAX_VALUE).build());
    }

    private static boolean validToken(String token) {
        return !token.isEmpty() && token.length() <= 8192 && token.matches("[A-Za-z0-9._~+%/-]+");
    }

    @Nullable
    public static String identity(@NonNull Context context) {
        java.util.Map<String, ?> values = AuthStore.getPreferences(context).getAll();
        Object expiry = values.get(REFRESH_EXPIRY);
        Object refresh = values.get(REFRESH);
        Object identity = values.get(ID);
        if (Boolean.TRUE.equals(values.get(REJECTED)) || !(expiry instanceof Long) || (Long) expiry <= System.currentTimeMillis()
            || !(refresh instanceof String) || ((String) refresh).isEmpty()
            || !(identity instanceof String)) return null;
        return (String) identity;
    }

    @Nullable
    public static String accessToken(@NonNull Context context) {
        String identity = identity(context);
        return identity == null ? null : accessTokenIfCurrent(context, identity);
    }

    @Nullable
    static String accessTokenIfCurrent(@NonNull Context context, @NonNull String expectedIdentity) {
        java.util.Map<String, ?> values = AuthStore.getPreferences(context).getAll();
        Object accessExpiry = values.get(ACCESS_EXPIRY);
        Object refreshExpiry = values.get(REFRESH_EXPIRY);
        Object token = values.get(ACCESS);
        if (!expectedIdentity.equals(values.get(ID)) || !(accessExpiry instanceof Long)
            || (Long) accessExpiry <= System.currentTimeMillis() || !(refreshExpiry instanceof Long)
            || (Long) refreshExpiry <= System.currentTimeMillis() || !(values.get(REFRESH) instanceof String)
            || ((String) values.get(REFRESH)).isEmpty() || !(token instanceof String)
            || ((String) token).isEmpty() || Boolean.TRUE.equals(values.get(REJECTED))) return null;
        return (String) token;
    }

    @Nullable
    static synchronized String refreshTokenIfCurrent(@NonNull Context context, @NonNull String expectedIdentity) {
        return expectedIdentity.equals(identity(context))
            ? AuthStore.getPreferences(context).getString(REFRESH, null) : null;
    }

    static synchronized void invalidateAccessIfCurrent(@NonNull Context context,
                                                       @NonNull String expectedIdentity,
                                                       @NonNull String expectedValue) {
        if (expectedIdentity.equals(identity(context))
            && expectedValue.equals(accessTokenIfCurrent(context, expectedIdentity))) {
            AuthStore.getPreferences(context).edit().remove(ACCESS).remove(ACCESS_EXPIRY).apply();
        }
    }

    static synchronized void rejectIfCurrent(@NonNull Context context, @NonNull String expectedIdentity) {
        if (expectedIdentity.equals(identity(context)))
            AuthStore.getPreferences(context).edit().putBoolean(REJECTED, true).apply();
    }

    /** Only accept secure, persistent cookies scoped to the official origin. */
    public static synchronized boolean replace(@NonNull Context context, @NonNull List<Cookie> cookies,
                                               @Nullable String expectedIdentity) {
        SharedPreferences prefs = AuthStore.getPreferences(context);
        if (expectedIdentity != null && !expectedIdentity.equals(identity(context))) return false;
        Cookie access = null;
        Cookie refresh = null;
        for (Cookie cookie : cookies) {
            if (!cookie.secure() || !cookie.persistent() || !cookie.matches(ORIGIN)
                || !cookie.domain().equals("nhentai.net") || cookie.value().isEmpty()
                || cookie.expiresAt() <= System.currentTimeMillis()) continue;
            if (cookie.name().equals("access_token")) access = cookie;
            if (cookie.name().equals("refresh_token")) refresh = cookie;
        }
        if (access == null || (refresh == null && expectedIdentity == null)) return false;
        SharedPreferences.Editor editor = prefs.edit()
            .putString(ID, expectedIdentity == null ? "user:" + UUID.randomUUID() : expectedIdentity)
            .putString(ACCESS, access.value()).putLong(ACCESS_EXPIRY, access.expiresAt())
            .remove(REJECTED);
        if (refresh != null) editor.putString(REFRESH, refresh.value()).putLong(REFRESH_EXPIRY, refresh.expiresAt());
        editor.apply();
        return true;
    }

    public static synchronized void clear(@NonNull Context context) {
        attempt++;
        AuthStore.getPreferences(context).edit().remove(ID).remove(ACCESS).remove(REFRESH)
            .remove(ACCESS_EXPIRY).remove(REFRESH_EXPIRY).remove(REJECTED).apply();
    }
}
