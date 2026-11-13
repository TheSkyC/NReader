// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ApiAuthInterceptor implements Interceptor {
    static final class RefreshRequest {
    }
    public static final class AccountIdentity {
        private final String accountId;
        private final long revision;
        private final java.util.function.BooleanSupplier current;

        public AccountIdentity(@NonNull String accountId, long revision,
                               @NonNull java.util.function.BooleanSupplier current) {
            this.accountId = accountId;
            this.revision = revision;
            this.current = current;
        }
    }
    public static final class ExpectedIdentity {
        private final String fingerprint;
        private final java.util.function.BooleanSupplier current;

        public ExpectedIdentity(@NonNull String fingerprint) {
            this(fingerprint, () -> true);
        }

        public ExpectedIdentity(@NonNull String fingerprint, @NonNull java.util.function.BooleanSupplier current) {
            this.fingerprint = fingerprint;
            this.current = current;
        }

        private void checkCurrent() throws IOException {
            if (!current.getAsBoolean()) throw new IOException("API operation expired");
        }
    }

    @NonNull
    public static String fingerprint(@NonNull String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    @NonNull
    private final Context context;

    public ApiAuthInterceptor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (request.tag(RefreshRequest.class) != null) {
            if (!isTrustedApiRequest(request.url(), Utility.ORIGINAL_URL)
                || !"/api/v2/auth/refresh".equals(request.url().encodedPath())
                || !"POST".equals(request.method()) || request.header("Authorization") != null)
                throw new IOException("Invalid refresh request");
            return chain.proceed(request);
        }
        AccountIdentity account = request.tag(AccountIdentity.class);
        if (account != null) {
            if (request.header("Authorization") != null || !isTrustedApiRequest(request.url(), Utility.ORIGINAL_URL)
                || !account.current.getAsBoolean()) throw new IOException("Invalid account operation");
            AccountRepository repository = AccountRuntime.repository();
            if (repository == null || repository.accountRevision(account.accountId) != account.revision)
                throw new IOException("Account changed");
            String token = SessionRefresh.accessAccount(context, account.accountId, account.revision);
            if (token == null || !account.current.getAsBoolean()
                || repository.accountRevision(account.accountId) != account.revision)
                throw new IOException("Account session unavailable");
            Request authenticated = request.newBuilder().header("Authorization", "User " + token).build();
            Response response = chain.proceed(authenticated);
            if (!account.current.getAsBoolean() || repository.accountRevision(account.accountId) != account.revision) {
                response.close();
                throw new IOException("Account operation expired");
            }
            if (response.code() == 401) repository.invalidateAccountAccess(account.accountId, account.revision, token);
            return response;
        }
        ExpectedIdentity expected = request.tag(ExpectedIdentity.class);
        long credentialGeneration = AuthStore.identityGeneration();
        if (credentialGeneration < 0 && AccountRuntime.repository() != null
            && !AccountRuntime.repository().isOpen() && AccountRuntime.openError() == null) {
            AccountRuntime.awaitInitialization();
            credentialGeneration = AuthStore.identityGeneration();
        }
        if (credentialGeneration < 0 && request.header("Authorization") == null
            && (isTrustedApiRequest(request.url(), Utility.ORIGINAL_URL)
                || isTrustedApiRequest(request.url(), Utility.getHost())))
            throw new IOException("Account identity is changing");
        if (expected != null) expected.checkCurrent();
        String session = AuthSessionStore.identity(context);
        if (expected != null && (request.header("Authorization") != null
            || !isTrustedApiRequest(request.url(), session != null ? Utility.ORIGINAL_URL : Utility.getHost())))
            throw new IOException("API identity is unavailable");
        if (session != null && request.header("Authorization") == null
            && isTrustedApiRequest(request.url(), Utility.ORIGINAL_URL)) {
            if (expected != null && !expected.fingerprint.equals(session)) throw new IOException("API identity changed");
            String token = SessionRefresh.access(context, session, null);
            if (token == null && expected == null && isPublicGet(request)
                && AuthSessionStore.isStoredIdentity(context, session))
                return proceedPublicForSession(chain, request, session);
            if (token == null || credentialGeneration != AuthStore.identityGeneration()
                || !session.equals(AuthSessionStore.identity(context)))
                throw new IOException("Account session is unavailable");
            Request authenticated = request.newBuilder()
                .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
                .header("Authorization", "User " + token).build();
            if (expected != null) expected.checkCurrent();
            Response response = chain.proceed(authenticated);
            if (expected != null && !expected.current.getAsBoolean()) {
                response.close();
                throw new IOException("API operation expired");
            }
            if (credentialGeneration < 0 || credentialGeneration != AuthStore.identityGeneration()
                || !session.equals(AuthSessionStore.identity(context))) {
                response.close();
                throw new IOException("API identity changed");
            }
            if (response.code() == 401 && "GET".equals(request.method())
                && session.equals(AuthSessionStore.identity(context))) {
                response.close();
                String refreshed = SessionRefresh.access(context, session, token);
                if (refreshed == null && expected == null && isPublicGet(request)
                    && AuthSessionStore.isStoredIdentity(context, session))
                    return proceedPublicForSession(chain, request, session);
                if (refreshed == null || credentialGeneration != AuthStore.identityGeneration()
                    || !session.equals(AuthSessionStore.identity(context)))
                    throw new IOException("Account session refresh failed");
                if (expected != null) expected.checkCurrent();
                Response retried = chain.proceed(authenticated.newBuilder()
                    .header("Authorization", "User " + refreshed).build());
                if (credentialGeneration < 0 || credentialGeneration != AuthStore.identityGeneration()
                    || !session.equals(AuthSessionStore.identity(context))
                    || (expected != null && !expected.current.getAsBoolean())) {
                    retried.close();
                    throw new IOException("API identity changed");
                }
                return retried;
            }
            if (response.code() == 401 && !"GET".equals(request.method())) {
                SessionRefresh.access(context, session, token);
                if (credentialGeneration != AuthStore.identityGeneration()
                    || !session.equals(AuthSessionStore.identity(context))) {
                    response.close();
                    throw new IOException("API identity changed");
                }
            }
            return response;
        }
        if (session != null && request.header("Authorization") == null) {
            if (expected != null) throw new IOException("API identity is unavailable");
            return chain.proceed(request);
        }
        if (session == null && AuthSessionStore.hasStoredSession(context)
            && (isTrustedApiRequest(request.url(), Utility.ORIGINAL_URL)
                || isTrustedApiRequest(request.url(), Utility.getHost()))) {
            if (expected != null) throw new IOException("Account session expired");
            return chain.proceed(request);
        }
        if (expected != null) {
            if (request.header("Authorization") != null || !isTrustedApiRequest(request.url(), Utility.getHost())
                || !AuthStore.hasValidApiKey(context)) throw new IOException("API identity is unavailable");
            String key = AuthStore.getApiKey(context);
            if (key == null || credentialGeneration != AuthStore.identityGeneration()
                || !expected.fingerprint.equals(fingerprint(key)))
                throw new IOException("API identity changed");
            Request authenticated = request.newBuilder()
                .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
                .header("Authorization", new AuthCredentials(AuthCredentials.Type.API_KEY, key).toAuthorizationHeader())
                .build();
            expected.checkCurrent();
            Response response = chain.proceed(authenticated);
            if (!expected.current.getAsBoolean()) {
                response.close();
                throw new IOException("API operation expired");
            }
            if (credentialGeneration != AuthStore.identityGeneration()
                || !expected.fingerprint.equals(AuthStore.activeIdentity(context))) {
                response.close();
                throw new IOException("API identity changed");
            }
            if (response.code() == 401 || response.code() == 403)
                AuthStore.setApiKeyValidationIfCurrent(context, key, credentialGeneration, false);
            else if (response.isSuccessful()) AuthStore.setApiKeyValidationIfCurrent(context, key, credentialGeneration, true);
            return response;
        }
        if (request.header("Authorization") != null || !isTrustedApiRequest(request.url(), Utility.getHost())) {
            return chain.proceed(request);
        }

        Request.Builder r = request.newBuilder();
        r.addHeader("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);

        if (!AuthStore.hasValidApiKey(context)) return chain.proceed(r.build());

        String key = AuthStore.getApiKey(context);
        if (key == null) return chain.proceed(r.build());
        if (credentialGeneration < 0 || credentialGeneration != AuthStore.identityGeneration())
            throw new IOException("API identity changed");
        String authorization = new AuthCredentials(AuthCredentials.Type.API_KEY, key).toAuthorizationHeader();

        Request authenticated =r.header("Authorization", authorization)
            .build();
        Response response = chain.proceed(authenticated);
        if (credentialGeneration != AuthStore.identityGeneration()) {
            response.close();
            throw new IOException("API identity changed");
        }
        if (response.code() == 401 || response.code() == 403) {
            AuthStore.setApiKeyValidationIfCurrent(context, key, credentialGeneration, false);
        } else if (response.isSuccessful()) {
            AuthStore.setApiKeyValidationIfCurrent(context, key, credentialGeneration, true);
        }
        return response;
    }

    private Response proceedPublicForSession(@NonNull Chain chain, @NonNull Request request,
                                             @NonNull String session) throws IOException {
        Response response = chain.proceed(request);
        if (!AuthSessionStore.isStoredIdentity(context, session)) {
            response.close();
            throw new IOException("API identity changed");
        }
        return response;
    }

    private static boolean isPublicGet(@NonNull Request request) {
        if (!"GET".equals(request.method())) return false;
        String path = request.url().encodedPath();
        if (path.endsWith("/favorite") || path.endsWith("/download")) return false;
        return path.equals("/api/v2/search") || path.equals("/api/v2/cdn")
            || path.equals("/api/v2/config") || path.equals("/api/v2/galleries")
            || path.startsWith("/api/v2/galleries/") || path.startsWith("/api/v2/tags/");
    }

    static boolean isTrustedApiRequest(@NonNull okhttp3.HttpUrl url, @NonNull String trustedHost) {
        return "https".equals(url.scheme())
            && url.host().equalsIgnoreCase(trustedHost)
            && url.port() == 443
            && url.encodedPath().startsWith("/api/v2/");
    }
}
