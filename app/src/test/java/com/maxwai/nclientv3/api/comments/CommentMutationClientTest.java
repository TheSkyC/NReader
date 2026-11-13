package com.maxwai.nclientv3.api.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.maxwai.nclientv3.settings.Global;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CommentMutationClientTest {
    private OkHttpClient previous;

    @Before public void setUp() { previous = Global.client; }
    @After public void tearDown() { Global.client = previous; }

    @Test public void publishesExpectedBodyAndParsesConfirmedComment() throws Exception {
        AtomicReference<JSONObject> captured = new AtomicReference<>();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer requestBody = new Buffer();
            chain.request().body().writeTo(requestBody);
            try { captured.set(new JSONObject(requestBody.readUtf8())); }
            catch (org.json.JSONException exception) { throw new IOException(exception); }
            assertEquals("/api/v2/galleries/42/comments", chain.request().url().encodedPath());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create("{\"id\":7,\"gallery_id\":42,"
                    + "\"post_date\":1,\"body\":\"Hello there\",\"poster\":{\"id\":5}}", null)).build();
        }).build();
        CommentMutationClient client = new CommentMutationClient(RuntimeEnvironment.getApplication(), "account", 1, () -> true);
        CommentMutationClient.Result result = client.publish(42, "Hello there", "captcha", "challenge", "nonce", "agent", "");
        assertTrue(result.confirmed);
        assertEquals(7, result.comment.getId());
        assertEquals("challenge", captured.get().getString("pow_challenge"));
        assertEquals("nonce", captured.get().getString("pow_nonce"));
        assertEquals("captcha", captured.get().getString("captcha_response"));
    }

    @Test public void rejectionAndAmbiguousResponsesAreNotReportedAsSuccessOrRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("limited").body(ResponseBody.create("{}", null)).build();
        }).build();
        CommentMutationClient client = new CommentMutationClient(RuntimeEnvironment.getApplication(), "account", 1, () -> true);
        CommentMutationClient.Result result = client.publish(42, "Hello there", "captcha", "challenge", "nonce", "agent", "");
        assertFalse(result.confirmed);
        assertEquals(429, result.status);
        assertEquals(1, calls.get());
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"gallery_id\":99}", null)).build()).build();
        assertThrows(IOException.class, () -> client.publish(42, "Hello there", "captcha", "challenge", "nonce", "agent", ""));
        assertThrows(IllegalArgumentException.class, () -> client.publish(42, "short", "captcha", "challenge", "nonce", "agent", ""));
    }

    @Test public void deleteRequiresStructuredSuccess() throws Exception {
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"success\":false}", null)).build()).build();
        CommentMutationClient client = new CommentMutationClient(RuntimeEnvironment.getApplication(), "account", 1, () -> true);
        assertFalse(client.delete(4).confirmed);
        client.cancel();
        assertThrows(IOException.class, () -> client.delete(4));
    }

    @Test public void distinguishesNewAccountRestrictionFromOtherRejections() throws Exception {
        AtomicReference<String> error = new AtomicReference<>("{\"error\":\"Your account is too new to post comments.\"}");
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
            .body(ResponseBody.create(error.get(), null)).build()).build();
        CommentMutationClient client = new CommentMutationClient(RuntimeEnvironment.getApplication(), "account", 1, () -> true);
        assertTrue(client.publish(42, "Hello there", "captcha", "challenge", "nonce", "agent", "").accountTooNew);
        error.set("{\"error\":\"New comments are disabled for this account.\"}");
        assertFalse(client.publish(42, "Hello there", "captcha", "challenge", "nonce", "agent", "").accountTooNew);
    }

    @Test public void verificationCookiesAreNotOverwrittenByGlobalCookieJar() {
        okhttp3.CookieJar jar = new okhttp3.CookieJar() {
            @Override public void saveFromResponse(okhttp3.HttpUrl url, java.util.List<okhttp3.Cookie> cookies) { }
            @Override public java.util.List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                return java.util.Collections.singletonList(new okhttp3.Cookie.Builder().name("cf_clearance")
                    .value("different").hostOnlyDomain("nhentai.net").build());
            }
        };
        OkHttpClient base = new OkHttpClient.Builder().cookieJar(jar).build();
        okhttp3.Request verified = new okhttp3.Request.Builder().url("https://nhentai.net/api/v2/galleries/42/comments")
            .header("Cookie", "cf_clearance=verified").build();
        assertEquals(okhttp3.CookieJar.NO_COOKIES, CommentMutationClient.clientFor(base, verified).cookieJar());
        assertEquals(jar, CommentMutationClient.clientFor(base, verified.newBuilder().removeHeader("Cookie").build()).cookieJar());
        assertFalse(CommentMutationClient.clientFor(base, verified).retryOnConnectionFailure());
        assertFalse(CommentMutationClient.clientFor(base, verified).followRedirects());
    }
}
