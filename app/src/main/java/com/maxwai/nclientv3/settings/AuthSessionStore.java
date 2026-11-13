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

    public static final class AccountMismatchException extends java.io.IOException {
        AccountMismatchException() {
            super("Login does not match the selected account");
        }
    }

    public static boolean hasStoredSession(@NonNull Context context) {
        if (AccountRuntime.repository() != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            return account != null && account.type == AccountVault.Type.PASSWORD;
        }
        return AuthStore.getPreferences(context).contains(ID);
    }

    static boolean isStoredIdentity(@NonNull Context context, @NonNull String expectedIdentity) {
        long generation = AuthStore.identityGeneration();
        if (generation < 0) return false;
        if (AccountRuntime.repository() != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            return account != null && account.type == AccountVault.Type.PASSWORD
                && expectedIdentity.equals(account.id) && generation == AuthStore.identityGeneration();
        }
        Object storedIdentity = AuthStore.getPreferences(context).getAll().get(ID);
        return expectedIdentity.equals(storedIdentity) && generation == AuthStore.identityGeneration();
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

    public static synchronized boolean completeAccountAttempt(@NonNull List<Cookie> cookies, long id,
                                                               long generation, @NonNull String loginName,
                                                               @NonNull String username, @Nullable String avatarUrl,
                                                               @Nullable String password, long userId,
                                                               @Nullable String targetId) throws java.io.IOException {
        return completeAccountAttemptForRole(cookies, id, generation, loginName, username, avatarUrl,
            password, userId, targetId, true) != null;
    }

    @Nullable
    public static synchronized String completeAccountAttemptForRole(@NonNull List<Cookie> cookies, long id,
            long generation, @NonNull String loginName, @NonNull String username, @Nullable String avatarUrl,
            @Nullable String password, long userId, @Nullable String targetId, boolean select) throws java.io.IOException {
        if (id != attempt) return null;
        AccountRepository repository = AccountRuntime.repository();
        if (repository == null || !repository.isOpen()) throw new java.io.IOException("Account storage is unavailable");
        Cookie access = null;
        Cookie refresh = null;
        for (Cookie cookie : cookies) {
            if (!cookie.secure() || !cookie.persistent() || !cookie.matches(ORIGIN)
                || !cookie.domain().equals("nhentai.net") || cookie.value().isEmpty()
                || cookie.expiresAt() <= System.currentTimeMillis()) continue;
            if (cookie.name().equals("access_token")) access = cookie;
            if (cookie.name().equals("refresh_token")) refresh = cookie;
        }
        if (access == null || refresh == null) return null;
        synchronized (repository) {
            if (generation < 0 || generation != repository.identityGeneration() || id != attempt) return null;
            AccountVault.State state = repository.snapshot();
            if (state == null) return null;
            AccountVault.Account existing = null;
            for (AccountVault.Account account : state.accounts) {
                if (targetId != null && account.id.equals(targetId)) existing = account;
                else if (targetId == null && userId > 0 && account.userId == userId
                    && account.type == AccountVault.Type.PASSWORD) existing = account;
            }
            if (targetId != null && (existing == null || existing.type != AccountVault.Type.PASSWORD
                || existing.userId <= 0 || userId <= 0 || existing.userId != userId))
                throw new AccountMismatchException();
            String accountId = existing == null ? "user:" + UUID.randomUUID() : existing.id;
            AccountVault.Account account = new AccountVault.Account(accountId, AccountVault.Type.PASSWORD,
                username, password, null, access.value(), refresh.value(), access.expiresAt(),
                refresh.expiresAt(), false, userId, loginName, avatarUrl);
            boolean saved = repository.putAccount(account, generation, select);
            attempt++;
            return saved ? accountId : null;
        }
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
        if (AccountRuntime.repository() != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            return account == null || account.type != AccountVault.Type.PASSWORD || account.rejected
                || account.refreshExpiry <= System.currentTimeMillis() || account.refreshToken == null
                ? null : account.id;
        }
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
        if (AccountRuntime.repository() != null) {
            long generation = AuthStore.identityGeneration();
            AccountVault.Account account = AccountRuntime.activeAccount();
            if (generation < 0 || account == null || !expectedIdentity.equals(account.id)
                || account.type != AccountVault.Type.PASSWORD || account.rejected || account.refreshToken == null
                || account.refreshExpiry <= System.currentTimeMillis() || account.accessExpiry <= System.currentTimeMillis()
                || account.accessToken == null || generation != AuthStore.identityGeneration()) return null;
            return account.accessToken;
        }
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
        if (AccountRuntime.repository() != null) {
            long generation = AuthStore.identityGeneration();
            AccountVault.Account account = AccountRuntime.activeAccount();
            return generation >= 0 && account != null && expectedIdentity.equals(identity(context))
                && generation == AuthStore.identityGeneration() ? account.refreshToken : null;
        }
        return expectedIdentity.equals(identity(context))
            ? AuthStore.getPreferences(context).getString(REFRESH, null) : null;
    }

    static synchronized void invalidateAccessIfCurrent(@NonNull Context context,
                                                       @NonNull String expectedIdentity,
                                                       @NonNull String expectedValue,
                                                       long expectedGeneration) {
        AccountRepository repository = AccountRuntime.repository();
        if (repository != null) {
            synchronized (repository) {
                if (expectedGeneration < 0 || expectedGeneration != AuthStore.identityGeneration()
                    || !expectedIdentity.equals(identity(context))) return;
                try {
                    repository.invalidateAccess(expectedIdentity, repository.identityGeneration(), expectedValue);
                } catch (java.io.IOException exception) {
                    com.maxwai.nclientv3.utility.LogUtility.w("Unable to invalidate account access: "
                        + exception.getClass().getSimpleName());
                }
            }
            return;
        }
        if (expectedGeneration >= 0 && expectedGeneration == AuthStore.identityGeneration()
            && expectedIdentity.equals(identity(context))
            && expectedValue.equals(accessTokenIfCurrent(context, expectedIdentity))) {
            AuthStore.getPreferences(context).edit().remove(ACCESS).remove(ACCESS_EXPIRY).apply();
        }
    }

    static synchronized void rejectIfCurrent(@NonNull Context context, @NonNull String expectedIdentity) {
        rejectIfCurrent(context, expectedIdentity, AuthStore.identityGeneration());
    }

    static synchronized void rejectIfCurrent(@NonNull Context context, @NonNull String expectedIdentity,
                                             long expectedGeneration) {
        AccountRepository repository = AccountRuntime.repository();
        if (repository != null) {
            synchronized (repository) {
                if (expectedGeneration < 0 || expectedGeneration != AuthStore.identityGeneration()
                    || !expectedIdentity.equals(identity(context))) return;
                try {
                    repository.reject(expectedIdentity, repository.identityGeneration());
                } catch (java.io.IOException exception) {
                    com.maxwai.nclientv3.utility.LogUtility.w("Unable to update account status: "
                        + exception.getClass().getSimpleName());
                }
            }
            return;
        }
        if (expectedGeneration < 0 || expectedGeneration != AuthStore.identityGeneration()) return;
        if (expectedIdentity.equals(identity(context))) {
            AuthStore.beginIdentityChange();
            try {
                AuthStore.getPreferences(context).edit().putBoolean(REJECTED, true).apply();
            } finally {
                AuthStore.endIdentityChange();
            }
        }
    }

    static synchronized boolean replaceIfCurrent(@NonNull Context context, @NonNull List<Cookie> cookies,
                                                  @NonNull String expectedIdentity, long expectedGeneration,
                                                  @NonNull String expectedRefresh) {
        AccountRepository repository = AccountRuntime.repository();
        if (repository == null) {
            if (expectedGeneration < 0 || expectedGeneration != AuthStore.identityGeneration()) return false;
            return replace(context, cookies, expectedIdentity);
        }
        Cookie access = null;
        Cookie refresh = null;
        for (Cookie cookie : cookies) {
            if (!cookie.secure() || !cookie.persistent() || !cookie.matches(ORIGIN)
                || !cookie.domain().equals("nhentai.net") || cookie.value().isEmpty()
                || cookie.expiresAt() <= System.currentTimeMillis()) continue;
            if (cookie.name().equals("access_token")) access = cookie;
            if (cookie.name().equals("refresh_token")) refresh = cookie;
        }
        if (access == null || refresh == null) return false;
        synchronized (repository) {
            if (expectedGeneration < 0 || expectedGeneration != AuthStore.identityGeneration()) return false;
            try {
                return repository.updateSession(expectedIdentity, repository.identityGeneration(), expectedRefresh,
                    access.value(), refresh.value(), access.expiresAt(), refresh.expiresAt());
            } catch (java.io.IOException exception) {
                com.maxwai.nclientv3.utility.LogUtility.w("Unable to save refreshed account session: "
                    + exception.getClass().getSimpleName());
                return false;
            }
        }
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
        if (expectedIdentity == null) AuthStore.beginIdentityChange();
        try {
            editor.apply();
        } finally {
            if (expectedIdentity == null) AuthStore.endIdentityChange();
        }
        return true;
    }

    public static synchronized void clear(@NonNull Context context) {
        attempt++;
        boolean changed = hasStoredSession(context);
        if (changed) AuthStore.beginIdentityChange();
        try {
            AuthStore.getPreferences(context).edit().remove(ID).remove(ACCESS).remove(REFRESH)
                .remove(ACCESS_EXPIRY).remove(REFRESH_EXPIRY).remove(REJECTED).apply();
        } finally {
            if (changed) AuthStore.endIdentityChange();
        }
    }
}
