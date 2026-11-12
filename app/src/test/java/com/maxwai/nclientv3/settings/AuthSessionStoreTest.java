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
    public void identityObserverRunsAfterRealWriteIsStable() {
        java.util.concurrent.atomic.AtomicLong observed = new java.util.concurrent.atomic.AtomicLong(-2);
        Runnable observer = () -> observed.set(AuthStore.identityGeneration());
        AuthStore.observeIdentityChanges(observer);
        try {
            AuthStore.saveApiKey(context, "observer-fixture", true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(observed.get() >= 0);
            assertEquals(AuthStore.identityGeneration(), observed.get());
        } finally {
            AuthStore.removeIdentityObserver(observer);
        }
    }

    @Test
    public void overlappingIdentityWritersHideGenerationUntilAllFinish() throws Exception {
        long initial = AuthStore.identityGeneration();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSecond = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch firstDone = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> {
            AuthStore.beginIdentityChange();
            entered.countDown();
            try { releaseFirst.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { AuthStore.endIdentityChange(); firstDone.countDown(); }
        });
        Thread second = new Thread(() -> {
            AuthStore.beginIdentityChange();
            entered.countDown();
            try { releaseSecond.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { AuthStore.endIdentityChange(); }
        });
        first.start();
        second.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(-1, AuthStore.identityGeneration());
            releaseFirst.countDown();
            assertTrue(firstDone.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(-1, AuthStore.identityGeneration());
            releaseSecond.countDown();
            second.join(5000);
            assertFalse(second.isAlive());
            assertTrue(AuthStore.identityGeneration() > initial);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            first.join(5000);
            second.join(5000);
        }
    }

    @Test
    public void overlappingRealCredentialWritesNotifyOnlyAfterBothFinish() throws Exception {
        android.content.SharedPreferences preferences = AuthStore.getPreferences(context);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch releaseKey = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSession = new java.util.concurrent.CountDownLatch(1);
        Context keyContext = blockingContext(preferences, entered, releaseKey);
        Context sessionContext = blockingContext(preferences, entered, releaseSession);
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong observed = new java.util.concurrent.atomic.AtomicLong(-1);
        Runnable observer = () -> {
            notifications.incrementAndGet();
            observed.set(AuthStore.identityGeneration());
        };
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AuthStore.observeIdentityChanges(observer);
        List<Cookie> cookies = validCookies();
        java.util.concurrent.atomic.AtomicBoolean saved = new java.util.concurrent.atomic.AtomicBoolean();
        Thread key = new Thread(() -> AuthStore.saveApiKey(keyContext, "overlap-fixture", true));
        Thread session = new Thread(() -> saved.set(AuthSessionStore.replace(sessionContext, cookies, null)));
        try {
            key.start();
            session.start();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(-1, AuthStore.identityGeneration());
            releaseKey.countDown();
            key.join(5000);
            assertFalse(key.isAlive());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(-1, AuthStore.identityGeneration());
            assertEquals(0, notifications.get());
            releaseSession.countDown();
            session.join(5000);
            assertFalse(session.isAlive());
            assertTrue(saved.get());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, notifications.get());
            assertEquals(AuthStore.identityGeneration(), observed.get());
            assertEquals(AuthSessionStore.identity(context), AuthStore.activeIdentity(context));
        } finally {
            releaseKey.countDown();
            releaseSession.countDown();
            key.join(5000);
            session.join(5000);
            AuthStore.removeIdentityObserver(observer);
        }
    }

    private Context blockingContext(android.content.SharedPreferences preferences,
                                    java.util.concurrent.CountDownLatch entered,
                                    java.util.concurrent.CountDownLatch release) {
        android.content.SharedPreferences wrapped = (android.content.SharedPreferences)
            java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    Object result = method.invoke(preferences, args);
                    if (!method.getName().equals("edit")) return result;
                    android.content.SharedPreferences.Editor editor = (android.content.SharedPreferences.Editor) result;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{android.content.SharedPreferences.Editor.class}, (editorProxy, editorMethod, editorArgs) -> {
                            if (editorMethod.getName().equals("apply")) {
                                entered.countDown();
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new AssertionError("Timed out waiting to release credential write");
                            }
                            Object value = editorMethod.invoke(editor, editorArgs);
                            return value == editor ? editorProxy : value;
                        });
                });
        return new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return "Auth".equals(name) ? wrapped : super.getSharedPreferences(name, mode);
            }
        };
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
