package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

@RunWith(RobolectricTestRunner.class)
public class AuthSessionStoreTest {
    private static final HttpUrl ORIGIN = HttpUrl.get("https://nhentai.net/");
    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        AuthStore.clear(context);
        AuthSessionStore.clear(context);
    }

    private Cookie cookie(String value) {
        return Cookie.parse(ORIGIN, value);
    }

    private List<Cookie> validCookies() {
        return Arrays.asList(cookie("access_token=access; Max-Age=3600; Secure; Path=/"),
            cookie("refresh_token=refresh; Max-Age=7200; Secure; HttpOnly; Path=/"));
    }

    @Test
    public void replacesAtomicallyAndKeepsApiKeySeparate() {
        AuthStore.saveApiKey(context, "key", true);
        assertTrue(AuthSessionStore.replace(context, validCookies(), null));
        String identity = AuthSessionStore.identity(context);
        assertNotNull(identity);
        assertEquals("access", AuthSessionStore.accessToken(context));
        assertEquals(identity, AuthStore.activeIdentity(context));
        AuthStore.clear(context);
        assertEquals(identity, AuthSessionStore.identity(context));
        AuthStore.saveApiKey(context, "new-key", true);
        AuthSessionStore.clear(context);
        assertEquals("new-key", AuthStore.getApiKey(context));
        assertNull(AuthSessionStore.identity(context));
    }

    @Test
    public void rejectsInvalidCookiesAndStaleAttempts() {
        assertFalse(AuthSessionStore.replace(context, Collections.singletonList(
            cookie("access_token=bad; Max-Age=100; Path=/")), null));
        long first = AuthSessionStore.beginAttempt();
        long second = AuthSessionStore.beginAttempt();
        assertFalse(AuthSessionStore.completeAttempt(context, validCookies(), first));
        assertTrue(AuthSessionStore.completeAttempt(context, validCookies(), second));
        String old = AuthSessionStore.identity(context);
        assertTrue(AuthSessionStore.replace(context, validCookies(), null));
        assertNotEquals(old, AuthSessionStore.identity(context));
        assertFalse(AuthSessionStore.replace(context, validCookies(), old));
        assertNull(AuthSessionStore.refreshTokenIfCurrent(context, old));
    }

    @Test
    public void expiredSessionDoesNotSwitchToSavedKey() {
        AuthStore.saveApiKey(context, "key", true);
        assertTrue(AuthSessionStore.replace(context, validCookies(), null));
        AuthStore.getPreferences(context).edit().putLong("session_refresh_expiry", 1L).apply();
        assertTrue(AuthSessionStore.hasStoredSession(context));
        assertNull(AuthStore.activeIdentity(context));
        AuthSessionStore.clear(context);
        assertEquals(ApiAuthInterceptor.fingerprint("key"), AuthStore.activeIdentity(context));
    }

    @Test
    public void apiRefreshTokenIsRetainedUntilRejected() {
        assertTrue(AuthSessionStore.replace(context, AuthSessionStore.tokenCookies("access", "refresh"), null));
        assertTrue(AuthStore.getPreferences(context).getLong("session_refresh_expiry", 0)
            > System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000);
        String identity = AuthSessionStore.identity(context);
        AuthSessionStore.rejectIfCurrent(context, identity);
        assertNull(AuthSessionStore.identity(context));
        assertTrue(AuthSessionStore.hasStoredSession(context));
    }
}
