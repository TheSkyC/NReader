package com.maxwai.nclientv3.components.views;

import android.app.Application;
import android.app.Dialog;
import android.os.Looper;

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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TurnstileDialogTest {
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
