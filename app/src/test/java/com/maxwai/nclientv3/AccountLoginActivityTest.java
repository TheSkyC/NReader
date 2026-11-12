package com.maxwai.nclientv3;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;

import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AccountLoginActivityTest {
    @Test public void recreationDoesNotRestorePassword() {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AccountLoginActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.account_username)).setText("fixture-user");
            ((EditText) activity.findViewById(R.id.account_password)).setText("fixture-password");
            controller.recreate();
            EditText password = controller.get().findViewById(R.id.account_password);
            assertEquals("", password.getText().toString());
        } finally {
            AuthSessionStore.clear(app);
            AuthStore.clear(app);
        }
    }

    @Test public void verificationFailureReconcilesSessionChangesDuringAttempt() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AccountLoginActivity activity = controller.get();
            java.lang.reflect.Method setBusy = AccountLoginActivity.class.getDeclaredMethod("setBusy", boolean.class);
            setBusy.setAccessible(true);
            setBusy.invoke(activity, true);
            assertTrue(AuthSessionStore.replace(app,
                AuthSessionStore.tokenCookies("fixture-access", "fixture-refresh"), null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.GONE, activity.findViewById(R.id.account_sign_out).getVisibility());
            java.lang.reflect.Method fail = AccountLoginActivity.class.getDeclaredMethod("fail", int.class);
            fail.setAccessible(true);
            fail.invoke(activity, R.string.account_verification_error);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.account_sign_out).getVisibility());
            TextView status = activity.findViewById(R.id.account_status);
            assertEquals(activity.getString(R.string.account_verification_error), status.getText().toString());
        } finally {
            AuthSessionStore.clear(app);
            AuthStore.clear(app);
        }
    }

    @Test public void emptyCredentialsAreRejectedBeforeVerification() {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AccountLoginActivity activity = controller.get();
            EditText username = activity.findViewById(R.id.account_username);
            EditText password = activity.findViewById(R.id.account_password);
            View signIn = activity.findViewById(R.id.account_sign_in);
            signIn.performClick();
            assertNotNull(username.getError());
            assertTrue(signIn.isEnabled());
            username.setText("fixture-user");
            signIn.performClick();
            assertNotNull(password.getError());
            assertTrue(signIn.isEnabled());
            assertEquals(View.GONE, activity.findViewById(R.id.account_progress).getVisibility());
        } finally {
            AuthSessionStore.clear(app);
            AuthStore.clear(app);
        }
    }

    @Test public void sessionStatusTracksBackgroundRejectionAndRemoval() {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class).setup()) {
            AccountLoginActivity activity = controller.get();
            TextView status = activity.findViewById(R.id.account_status);
            View signOut = activity.findViewById(R.id.account_sign_out);
            assertEquals(activity.getString(R.string.account_signed_out), status.getText().toString());
            assertEquals(View.GONE, signOut.getVisibility());

            assertTrue(AuthSessionStore.replace(app,
                AuthSessionStore.tokenCookies("fixture-access", "fixture-refresh"), null));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(activity.getString(R.string.account_session_saved), status.getText().toString());
            assertEquals(View.VISIBLE, signOut.getVisibility());

            app.getSharedPreferences("Auth", 0).edit().putBoolean("session_rejected", true).commit();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(activity.getString(R.string.account_session_expired), status.getText().toString());
            assertEquals(View.VISIBLE, signOut.getVisibility());

            AuthSessionStore.clear(app);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(activity.getString(R.string.account_signed_out), status.getText().toString());
            assertEquals(View.GONE, signOut.getVisibility());
        } finally {
            AuthSessionStore.clear(app);
            AuthStore.clear(app);
        }
    }
}
