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
    public static final class ExpectedIdentity {
        private final String fingerprint;

        public ExpectedIdentity(@NonNull String fingerprint) {
            this.fingerprint = fingerprint;
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
        ExpectedIdentity expected = request.tag(ExpectedIdentity.class);
        if (expected != null) {
            if (request.header("Authorization") != null || !isTrustedApiRequest(request.url(), Utility.getHost())
                || !AuthStore.hasValidApiKey(context)) throw new IOException("API identity is unavailable");
            String key = AuthStore.getApiKey(context);
            if (key == null || !expected.fingerprint.equals(fingerprint(key)))
                throw new IOException("API identity changed");
            Request authenticated = request.newBuilder()
                .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
                .header("Authorization", new AuthCredentials(AuthCredentials.Type.API_KEY, key).toAuthorizationHeader())
                .build();
            Response response = chain.proceed(authenticated);
            if (response.code() == 401 || response.code() == 403)
                AuthStore.setApiKeyValidationIfCurrent(context, key, false);
            else if (response.isSuccessful()) AuthStore.setApiKeyValidationIfCurrent(context, key, true);
            return response;
        }
        if (request.header("Authorization") != null || !isTrustedApiRequest(request.url(), Utility.getHost())) {
            return chain.proceed(request);
        }

        Request.Builder r = request.newBuilder();
        r.addHeader("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);

        if (!AuthStore.hasValidApiKey(context)) return chain.proceed(r.build());

        String authorization = AuthStore.getAuthorizationHeader(context);
        if (authorization == null) return chain.proceed(r.build());

        Request authenticated =r.header("Authorization", authorization)
            .build();
        Response response = chain.proceed(authenticated);
        if (response.code() == 401 || response.code() == 403) {
            AuthStore.setApiKeyValidation(context, false);
        } else if (response.isSuccessful()) {
            AuthStore.setApiKeyValidation(context, true);
        }
        return response;
    }

    static boolean isTrustedApiRequest(@NonNull okhttp3.HttpUrl url, @NonNull String trustedHost) {
        return "https".equals(url.scheme())
            && url.host().equalsIgnoreCase(trustedHost)
            && url.port() == 443
            && url.encodedPath().startsWith("/api/v2/");
    }
}
