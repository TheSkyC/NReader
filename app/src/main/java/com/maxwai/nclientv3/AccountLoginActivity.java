package com.maxwai.nclientv3;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.views.TurnstileDialog;
import com.maxwai.nclientv3.loginapi.NhentaiLoginApi;
import com.maxwai.nclientv3.loginapi.PowSolver;
import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AccountLoginActivity extends GeneralActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private EditText username;
    private EditText password;
    private TextView status;
    private ProgressBar progress;
    private MaterialButton signIn;
    private MaterialButton signOut;
    private Dialog verification;
    private NhentaiLoginApi.CancelHandle requestHandle;
    private long attempt;
    private boolean busy;
    private boolean stopped;
    private boolean displayedSession;
    @Nullable private String displayedIdentity;
    private final SharedPreferences.OnSharedPreferenceChangeListener authObserver = (preferences, key) ->
        postIfActive(this::refreshSessionStatusIfChanged);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_login);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        username = findViewById(R.id.account_username);
        password = findViewById(R.id.account_password);
        status = findViewById(R.id.account_status);
        progress = findViewById(R.id.account_progress);
        signIn = findViewById(R.id.account_sign_in);
        signOut = findViewById(R.id.account_sign_out);
        signIn.setOnClickListener(view -> start());
        signOut.setOnClickListener(view -> confirmSignOut());
        showIdle();
        AuthStore.observeChanges(this, authObserver);
    }

    private void showIdle() {
        setBusy(false);
        boolean stored = AuthSessionStore.hasStoredSession(this);
        String identity = AuthSessionStore.identity(this);
        displayedSession = stored;
        displayedIdentity = identity;
        status.setText(!stored ? R.string.account_signed_out
            : identity != null ? R.string.account_session_saved : R.string.account_session_expired);
        signOut.setVisibility(stored ? View.VISIBLE : View.GONE);
    }

    private void refreshSessionStatusIfChanged() {
        if (busy) return;
        boolean stored = AuthSessionStore.hasStoredSession(this);
        String identity = AuthSessionStore.identity(this);
        if (stored != displayedSession || !Objects.equals(identity, displayedIdentity)) showIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessionStatusIfChanged();
    }

    private void start() {
        if (busy) return;
        if (username.getText().toString().trim().isEmpty()) {
            username.setError(getString(R.string.account_enter_username));
            return;
        }
        if (password.getText().length() == 0) {
            password.setError(getString(R.string.account_enter_password));
            return;
        }
        cancelled.set(false);
        attempt = AuthSessionStore.beginAttempt();
        requestHandle = new NhentaiLoginApi.CancelHandle();
        setBusy(true);
        status.setText(R.string.account_preparing_verification);
        worker.execute(() -> {
            try {
                String key = NhentaiLoginApi.captchaSiteKey(getApplicationContext(), requestHandle);
                postIfActive(() -> verification = TurnstileDialog.show(this, key, new TurnstileDialog.Callback() {
                    @Override public void onToken(@NonNull String token, @NonNull String userAgent,
                                                  @NonNull String protectionCookies) {
                        submit(token, userAgent, protectionCookies);
                    }
                    @Override public void onCancelled() {
                        AuthSessionStore.cancelAttempt(attempt);
                        showIdle();
                    }
                    @Override public void onFailure() { fail(R.string.account_verification_error); }
                }));
            } catch (IOException | RuntimeException exception) {
                LogUtility.w("Unable to prepare account verification: " + exception.getClass().getSimpleName());
                postIfActive(() -> fail(R.string.account_verification_error));
            }
        });
    }

    private void submit(@NonNull String captcha, @NonNull String userAgent, @NonNull String protectionCookies) {
        String user = username.getText().toString().trim();
        String pass = password.getText().toString();
        password.setText("");
        long currentAttempt = attempt;
        status.setText(R.string.account_signing_in);
        worker.execute(() -> {
            try {
                NhentaiLoginApi.Challenge challenge = NhentaiLoginApi.challenge(getApplicationContext(), requestHandle);
                String nonce = PowSolver.solve(challenge.value, challenge.difficulty, 60000, cancelled::get);
                if (cancelled.get()) return;
                NhentaiLoginApi.login(getApplicationContext(), user, pass, captcha, challenge, nonce,
                    currentAttempt, requestHandle, userAgent, protectionCookies);
                postIfActive(() -> {
                    Login.updateUser(null);
                    Login.isLogged(this);
                    showIdle();
                });
            } catch (IOException | RuntimeException exception) {
                LogUtility.w("Account sign-in failed: " + exception.getClass().getSimpleName());
                int message = exception instanceof NhentaiLoginApi.ProtectionException
                    ? R.string.account_protection_error : R.string.account_login_error;
                postIfActive(() -> fail(message));
            }
        });
    }

    private void fail(int message) {
        AuthSessionStore.cancelAttempt(attempt);
        showIdle();
        status.setText(message);
    }

    private void setBusy(boolean value) {
        busy = value;
        signIn.setEnabled(!value);
        signOut.setEnabled(!value);
        username.setEnabled(!value);
        password.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this).setMessage(R.string.account_sign_out_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.account_sign_out, (dialog, which) -> {
                AuthSessionStore.clear(this);
                Login.updateUser(null);
                Login.isLogged(this);
                showIdle();
            }).show();
    }

    private void postIfActive(Runnable task) {
        runOnUiThread(() -> {
            if (!stopped && !isFinishing() && !isDestroyed()) task.run();
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        AuthStore.removeChangesObserver(this, authObserver);
        cancelled.set(true);
        if (requestHandle != null) requestHandle.cancel();
        AuthSessionStore.cancelAttempt(attempt);
        if (verification != null) verification.dismiss();
        worker.shutdownNow();
        super.onDestroy();
    }
}
