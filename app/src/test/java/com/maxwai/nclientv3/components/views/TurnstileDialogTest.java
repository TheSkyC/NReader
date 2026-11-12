package com.maxwai.nclientv3.components.views;

import android.app.Application;
import android.app.Dialog;
import android.os.Looper;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import com.maxwai.nclientv3.AccountLoginActivity;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TurnstileDialogTest {
    @Test public void readsOnlyBoundedJsonStringResults() throws Exception {
        String encoded = org.json.JSONObject.quote("{\"state\":\"token\",\"value\":\"fixture\"}");
        assertEquals("fixture", TurnstileDialog.parseResult(encoded).getString("value"));
        assertNull(TurnstileDialog.parseResult("null"));
        assertNull(TurnstileDialog.parseResult("{\"state\":\"token\"}"));
        assertNull(TurnstileDialog.parseResult("not-json"));
        assertNull(TurnstileDialog.parseResult(new String(new char[16385]).replace('\0', 'x')));
    }

    @Test public void deliversPolledTokenOnceAndIgnoresLateResult() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger tokens = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) {
                    assertEquals("fixture-token", token);
                    tokens.incrementAndGet();
                }
                @Override public void onCancelled() { cancelled.incrementAndGet(); }
                @Override public void onFailure() { throw new AssertionError(); }
            });
            WebView webView = findWebView(dialog.getWindow().getDecorView());
            assertNotNull(webView);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowWebView shadow = Shadows.shadowOf(webView);
            assertNull(shadow.getJavascriptInterface("NReaderVerification"));
            String html = shadow.getLastLoadDataWithBaseURL().data;
            assertTrue(html.indexOf("function ready()") < html.indexOf("https://challenges.cloudflare.com/turnstile/"));
            android.webkit.ValueCallback<String> callback = shadow.getLastEvaluatedJavascriptCallback();
            assertNotNull(callback);
            String result = org.json.JSONObject.quote("{\"state\":\"token\",\"value\":\"fixture-token\"}");
            callback.onReceiveValue(result);
            callback.onReceiveValue(result);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);
            assertEquals(1, tokens.get());
            assertEquals(0, cancelled.get());
        }
    }

    @Test public void tokenArrivingAfterDismissalCannotCompleteVerification() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger tokens = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { tokens.incrementAndGet(); }
                @Override public void onCancelled() { cancelled.incrementAndGet(); }
                @Override public void onFailure() { throw new AssertionError(); }
            });
            WebView webView = findWebView(dialog.getWindow().getDecorView());
            assertNotNull(webView);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.webkit.ValueCallback<String> callback = Shadows.shadowOf(webView).getLastEvaluatedJavascriptCallback();
            assertNotNull(callback);
            dialog.dismiss();
            callback.onReceiveValue(org.json.JSONObject.quote("{\"state\":\"token\",\"value\":\"fixture-token\"}"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);
            assertEquals(0, tokens.get());
            assertEquals(1, cancelled.get());
        }
    }

    @Test public void pendingResultIsPolledAgainAndErrorEndsVerification() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger failures = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { throw new AssertionError(); }
                @Override public void onCancelled() { throw new AssertionError(); }
                @Override public void onFailure() { failures.incrementAndGet(); }
            });
            WebView webView = findWebView(dialog.getWindow().getDecorView());
            assertNotNull(webView);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowWebView shadow = Shadows.shadowOf(webView);
            android.webkit.ValueCallback<String> first = shadow.getLastEvaluatedJavascriptCallback();
            assertNotNull(first);
            first.onReceiveValue(org.json.JSONObject.quote("{\"state\":\"pending\"}"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS);
            android.webkit.ValueCallback<String> second = shadow.getLastEvaluatedJavascriptCallback();
            assertNotNull(second);
            org.junit.Assert.assertNotSame(first, second);
            second.onReceiveValue(org.json.JSONObject.quote("{\"state\":\"error\"}"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);
            assertEquals(1, failures.get());
        }
    }

    @Test public void timeoutAfterDismissalDoesNotReportFailure() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { throw new AssertionError(); }
                @Override public void onCancelled() { cancelled.incrementAndGet(); }
                @Override public void onFailure() { failures.incrementAndGet(); }
            });
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(59, TimeUnit.SECONDS);
            dialog.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
            assertEquals(1, cancelled.get());
            assertEquals(0, failures.get());
        }
    }

    @Test public void finalPollAcceptsTokenBeforeTimeoutFailure() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger tokens = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { tokens.incrementAndGet(); }
                @Override public void onCancelled() { throw new AssertionError(); }
                @Override public void onFailure() { failures.incrementAndGet(); }
            });
            WebView webView = findWebView(dialog.getWindow().getDecorView());
            assertNotNull(webView);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
            android.webkit.ValueCallback<String> finalPoll = Shadows.shadowOf(webView).getLastEvaluatedJavascriptCallback();
            assertNotNull(finalPoll);
            finalPoll.onReceiveValue(org.json.JSONObject.quote("{\"state\":\"token\",\"value\":\"fixture-token\"}"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
            assertEquals(1, tokens.get());
            assertEquals(0, failures.get());
        }
    }

    private static WebView findWebView(android.view.View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                WebView found = findWebView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void dismissingVerificationCancelsOnceAndRemovesTimeout() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            Dialog dialog = TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { throw new AssertionError(); }
                @Override public void onCancelled() { cancelled.incrementAndGet(); }
                @Override public void onFailure() { failures.incrementAndGet(); }
            });
            dialog.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);
            assertEquals(1, cancelled.get());
            assertEquals(0, failures.get());
        }
    }

    @Test public void verificationTimeoutFailsOnlyOnce() {
        try (org.robolectric.android.controller.ActivityController<AccountLoginActivity> controller =
                 Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            TurnstileDialog.show(controller.get(), "fixture-key", new TurnstileDialog.Callback() {
                @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                              @NonNull String protectionCookies) { throw new AssertionError(); }
                @Override public void onCancelled() { cancelled.incrementAndGet(); }
                @Override public void onFailure() { failures.incrementAndGet(); }
            });
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS);
            assertEquals(0, cancelled.get());
            assertEquals(1, failures.get());
        }
    }
}
