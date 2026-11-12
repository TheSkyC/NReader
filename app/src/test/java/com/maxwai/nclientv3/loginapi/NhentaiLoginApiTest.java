package com.maxwai.nclientv3.loginapi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class NhentaiLoginApiTest {
    @Test public void siteKeyCannotBreakOutOfScript() {
        assertTrue(NhentaiLoginApi.validSiteKey("0x4AAAAAABmfxjJLaRcDE3KW"));
        assertFalse(NhentaiLoginApi.validSiteKey("</script><script>alert(1)</script>"));
        assertFalse(NhentaiLoginApi.validSiteKey("key\";alert(1)"));
        assertFalse(NhentaiLoginApi.validSiteKey(""));
    }

    @Test public void loginResponseRequiresUserAndStringTokens() throws Exception {
        JSONObject valid = new JSONObject("{\"success\":true,\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}");
        assertEquals(2, NhentaiLoginApi.parseTokens(valid).size());
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"success\":false,\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"success\":\"false\",\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"success\":null,\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertEquals(2, NhentaiLoginApi.parseTokens(new JSONObject(
            "{\"user\":{},\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")).size());
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"user\":{},\"access_token\":123,\"refresh_token\":\"refresh-fixture\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseTokens(
            new JSONObject("{\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}")));
        assertTrue(NhentaiLoginApi.parseTokens(new JSONObject(
            "{\"user\":{},\"access_token\":\"\",\"refresh_token\":\"refresh-fixture\"}")).isEmpty());
    }

    @Test public void configurationRequiresStringFieldsAndBoundedDifficulty() throws Exception {
        assertEquals("test-key", NhentaiLoginApi.parseCaptchaSiteKey(
            new JSONObject("{\"provider\":\"turnstile\",\"site_key\":\"test-key\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseCaptchaSiteKey(
            new JSONObject("{\"provider\":\"turnstile\",\"site_key\":123}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseCaptchaSiteKey(
            new JSONObject("{\"provider\":\"turnstile\",\"site_key\":null}")));
        NhentaiLoginApi.Challenge challenge = NhentaiLoginApi.parseChallenge(
            new JSONObject("{\"challenge\":\"fixture\",\"difficulty\":8}"));
        assertEquals("fixture", challenge.value);
        assertEquals(8, challenge.difficulty);
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseChallenge(
            new JSONObject("{\"challenge\":123,\"difficulty\":8}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseChallenge(
            new JSONObject("{\"challenge\":null,\"difficulty\":8}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseChallenge(
            new JSONObject("{\"challenge\":\"fixture\",\"difficulty\":\"8\"}")));
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseChallenge(
            new JSONObject("{\"challenge\":\"fixture\",\"difficulty\":257}")));
    }

    @Test public void loginResultKeepsValidatedProfileAndRejectsUntrustedAvatar() throws Exception {
        JSONObject body = new JSONObject("{\"user\":{\"id\":12,\"username\":\"fixture\","
            + "\"avatar_url\":\"https://i.nhentai.net/avatars/12.png\"},"
            + "\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"}");
        NhentaiLoginApi.LoginResult result = NhentaiLoginApi.parseLoginResult(body);
        assertEquals(12, result.userId);
        assertEquals("fixture", result.username);
        assertEquals("https://i.nhentai.net/avatars/12.png", result.avatarUrl);
        body.getJSONObject("user").put("avatar_url", "https://nhentai.net.evil.example/avatar.png");
        assertEquals(null, NhentaiLoginApi.parseLoginResult(body).avatarUrl);
        body.getJSONObject("user").put("id", "12");
        assertThrows(java.io.IOException.class, () -> NhentaiLoginApi.parseLoginResult(body));
    }

    @Test public void explicitWebViewCookiesSurviveTheNetworkCookieBridge() throws Exception {
        Application app = org.robolectric.RuntimeEnvironment.getApplication();
        okhttp3.OkHttpClient previous = com.maxwai.nclientv3.settings.Global.client;
        okhttp3.Cookie stale = new okhttp3.Cookie.Builder().name("cf_clearance").value("old-fixture")
            .hostOnlyDomain("nhentai.net").path("/").secure().build();
        okhttp3.Cookie session = new okhttp3.Cookie.Builder().name("sessionid").value("session-fixture")
            .hostOnlyDomain("nhentai.net").path("/").secure().build();
        okhttp3.CookieJar source = new okhttp3.CookieJar() {
            @Override public void saveFromResponse(okhttp3.HttpUrl url, java.util.List<okhttp3.Cookie> cookies) { }
            @Override public java.util.List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                return java.util.Arrays.asList(stale, session);
            }
        };
        com.maxwai.nclientv3.settings.Global.client = new okhttp3.OkHttpClient.Builder().cookieJar(source).build();
        try {
            okhttp3.Request request = new okhttp3.Request.Builder().url("https://nhentai.net/api/v2/auth/login")
                .header("Cookie", "cf_clearance=new-fixture").build();
            assertEquals("cf_clearance=new-fixture", bridgedCookie(request, NhentaiLoginApi.client(app, true).cookieJar()));
            assertEquals("cf_clearance=old-fixture", bridgedCookie(request, NhentaiLoginApi.client(app, false).cookieJar()));
        } finally {
            com.maxwai.nclientv3.settings.Global.client = previous;
        }
    }

    private static String bridgedCookie(okhttp3.Request request, okhttp3.CookieJar jar) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> cookie = new java.util.concurrent.atomic.AtomicReference<>();
        okhttp3.Interceptor.Chain chain = (okhttp3.Interceptor.Chain) java.lang.reflect.Proxy.newProxyInstance(
            okhttp3.Interceptor.Chain.class.getClassLoader(), new Class<?>[]{okhttp3.Interceptor.Chain.class},
            (proxy, method, args) -> {
                if (method.getName().equals("request")) return request;
                if (method.getName().equals("getCookieJar")) return jar;
                if (method.getName().equals("proceed")) {
                    okhttp3.Request sent = (okhttp3.Request) args[0];
                    cookie.set(sent.header("Cookie"));
                    return new okhttp3.Response.Builder().request(sent).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200).message("fixture").body(okhttp3.ResponseBody.create("", null)).build();
                }
                throw new UnsupportedOperationException(method.getName());
            });
        new okhttp3.internal.http.BridgeInterceptor().intercept(chain).close();
        return cookie.get();
    }
}
