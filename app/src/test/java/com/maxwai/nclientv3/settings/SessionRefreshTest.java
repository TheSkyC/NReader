package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Request;
import okhttp3.RequestBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SessionRefreshTest {
    private Application context;
    private OkHttpClient original;
    private final HttpUrl origin = HttpUrl.get("https://nhentai.net/");

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        original = Global.client;
        AuthSessionStore.clear(context);
        assertTrue(AuthSessionStore.replace(context, cookies("old"), null));
    }

    @After public void cleanup() {
        Global.client = original;
        AuthSessionStore.clear(context);
    }

    private java.util.List<Cookie> cookies(String value) {
        return Arrays.asList(Cookie.parse(origin, "access_token=" + value + "; Max-Age=3600; Secure; Path=/"),
            Cookie.parse(origin, "refresh_token=refresh; Max-Age=7200; Secure; HttpOnly; Path=/"));
    }

    private Response response(okhttp3.Request request) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"access_token\":\"new\",\"refresh_token\":\"new-refresh\"}", null)).build();
    }

    @Test public void refreshReusesNewTokenForConcurrentRejection() {
        String identity = AuthSessionStore.identity(context);
        int[] calls = {0};
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls[0]++;
            assertEquals("/api/v2/auth/refresh", chain.request().url().encodedPath());
            return response(chain.request());
        }).build();
        assertEquals("new", SessionRefresh.access(context, identity, "old"));
        assertEquals("new", SessionRefresh.access(context, identity, "old"));
        assertEquals(1, calls[0]);
    }

    @Test public void refreshCannotOverwriteReplacementAccount() {
        String identity = AuthSessionStore.identity(context);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertTrue(AuthSessionStore.replace(context, cookies("replacement"), null));
            return response(chain.request());
        }).build();
        assertNull(SessionRefresh.access(context, identity, "old"));
        assertNotEquals(identity, AuthSessionStore.identity(context));
        assertEquals("replacement", AuthSessionStore.accessToken(context));
    }

    @Test public void rejectedWriteRefreshesButIsNotReplayed() throws Exception {
        String identity = AuthSessionStore.identity(context);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request())).build();
        int[] writes = {0};
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(context))
            .addInterceptor(chain -> {
                writes[0]++;
                boolean fresh = "User new".equals(chain.request().header("Authorization"));
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(fresh ? 200 : 401).message(fresh ? "OK" : "Unauthorized")
                    .body(ResponseBody.create("", null)).build();
            }).build();
        Request request = new Request.Builder().url("https://nhentai.net/api/v2/galleries/1/favorite")
            .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity))
            .post(RequestBody.EMPTY).build();
        try (Response first = client.newCall(request).execute()) {
            assertEquals(401, first.code());
        }
        assertEquals(1, writes[0]);
        try (Response next = client.newCall(request).execute()) {
            assertEquals(200, next.code());
        }
        assertEquals(2, writes[0]);
    }

    @Test public void failedRefreshDoesNotReuseRejectedAccessToken() {
        String identity = AuthSessionStore.identity(context);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").body(ResponseBody.create("", null)).build()).build();
        assertNull(SessionRefresh.access(context, identity, "old"));
        assertNull(AuthSessionStore.accessToken(context));
        assertNull(SessionRefresh.access(context, identity, null));
    }

    @Test public void rejectedRefreshStillAllowsPublicReadWithoutCredentials() throws Exception {
        Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").body(ResponseBody.create("", null)).build()).build();
        java.util.List<String> authorization = new java.util.ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(context))
            .addInterceptor(chain -> {
                authorization.add(chain.request().header("Authorization"));
                boolean authenticated = chain.request().header("Authorization") != null;
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(authenticated ? 401 : 200).message("fixture")
                    .body(ResponseBody.create("{}", null)).build();
            }).build();
        try (Response response = client.newCall(new Request.Builder()
            .url("https://nhentai.net/api/v2/galleries").build()).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(2, authorization.size());
        assertEquals("User old", authorization.get(0));
        assertNull(authorization.get(1));
        assertNull(AuthSessionStore.identity(context));
        assertTrue(AuthSessionStore.hasStoredSession(context));
    }

    @Test public void rejectedRefreshDoesNotRetryAccountBoundReadAnonymously() throws Exception {
        String identity = AuthSessionStore.identity(context);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").body(ResponseBody.create("", null)).build()).build();
        int[] requests = {0};
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(context))
            .addInterceptor(chain -> {
                requests[0]++;
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(401).message("Unauthorized").body(ResponseBody.create("", null)).build();
            }).build();
        try {
            client.newCall(new Request.Builder().url("https://nhentai.net/api/v2/favorites")
                .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity))
                .build()).execute();
            org.junit.Assert.fail("Account-bound reads must not fall back to an anonymous request");
        } catch (java.io.IOException expected) {
            assertEquals(1, requests[0]);
            assertNull(AuthSessionStore.identity(context));
        }
    }

    @Test public void accessTokenIsNotUrlDecoded() {
        assertTrue(AuthSessionStore.replace(context, AuthSessionStore.tokenCookies("a%2Fb+c", "refresh"), null));
        String identity = AuthSessionStore.identity(context);
        assertEquals("a%2Fb+c", SessionRefresh.access(context, identity, null));
    }
}
