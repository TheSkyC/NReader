// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ApiAuthInterceptor implements Interceptor {
    @NonNull
    private final Context context;

    public ApiAuthInterceptor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
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
