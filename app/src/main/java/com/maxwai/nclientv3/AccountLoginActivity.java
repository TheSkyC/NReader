package com.maxwai.nclientv3;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.CheckBox;
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
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountVault;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AccountLoginActivity extends GeneralActivity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EDITED_CREDENTIALS = "edited_credentials";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private EditText username;
    private EditText password;
    private CheckBox rememberPassword;
    private TextView status;
    private ProgressBar progress;
    private MaterialButton signIn;
    private MaterialButton signOut;
    private Dialog verification;
    private NhentaiLoginApi.CancelHandle requestHandle;
    private long attempt;
    private long accountGeneration;
    @Nullable private String targetAccountId;
    private boolean restoredAccount;
    private boolean recreated;
    private boolean restoreSavedPassword = true;
    private boolean busy;
    private boolean stopped;
    private boolean displayedSession;
    @Nullable private String displayedIdentity;
    private final Runnable identityObserver = () -> postIfActive(() -> {
        restoreAccount();
        refreshSessionStatusIfChanged();
    });
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
        rememberPassword = findViewById(R.id.account_remember_password);
        targetAccountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        recreated = savedInstanceState != null;
        restoreSavedPassword = !recreated || !savedInstanceState.getBoolean(EDITED_CREDENTIALS, true);
        status = findViewById(R.id.account_status);
        progress = findViewById(R.id.account_progress);
        signIn = findViewById(R.id.account_sign_in);
        signOut = findViewById(R.id.account_sign_out);
        signIn.setOnClickListener(view -> start());
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            start();
            return true;
        });
        signOut.setOnClickListener(view -> confirmSignOut());
        showIdle();
        restoreAccount();
        AuthStore.observeChanges(this, authObserver);
        AuthStore.observeIdentityChanges(identityObserver);
    }

    private void restoreAccount() {
        if (restoredAccount || targetAccountId == null) return;
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        if (state == null) return;
        restoredAccount = true;
        for (AccountVault.Account account : state.accounts) {
            if (!account.id.equals(targetAccountId) || account.type != AccountVault.Type.PASSWORD) continue;
            if (!recreated) username.setText(account.loginName == null ? account.username : account.loginName);
            if (restoreSavedPassword && account.password != null && password.length() == 0) {
                password.setText(account.password);
                if (!recreated) rememberPassword.setChecked(true);
            }
            break;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        AccountVault.Account account = targetAccount();
        String loginName = account == null ? null : account.loginName == null ? account.username : account.loginName;
        String savedPassword = account == null || account.password == null ? "" : account.password;
        boolean edited = account == null || !Objects.equals(loginName, username.getText().toString())
            || !savedPassword.equals(password.getText().toString());
        outState.putBoolean(EDITED_CREDENTIALS, edited);
        super.onSaveInstanceState(outState);
    }

    private void showIdle() {
        setBusy(false);
        if (AccountRuntime.repository() != null) {
            AccountVault.Account selected = targetAccount();
            boolean stored = selected != null;
            boolean valid = stored && !selected.rejected && selected.refreshToken != null
                && selected.refreshExpiry > System.currentTimeMillis();
            displayedSession = stored;
            displayedIdentity = valid ? selected.id : null;
            status.setText(targetAccountId == null ? R.string.accounts_add
                : !stored ? R.string.account_storage_error
                : valid ? R.string.account_session_saved : R.string.account_session_expired);
            signOut.setVisibility(View.GONE);
            return;
        }
        boolean stored = AuthSessionStore.hasStoredSession(this);
        String identity = AuthSessionStore.identity(this);
        displayedSession = stored;
        displayedIdentity = identity;
        status.setText(!stored ? R.string.account_signed_out
            : identity != null ? R.string.account_session_saved : R.string.account_session_expired);
        signOut.setVisibility(stored ? View.VISIBLE : View.GONE);
    }

    @Nullable private AccountVault.Account targetAccount() {
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        if (state == null || targetAccountId == null) return null;
        for (AccountVault.Account account : state.accounts) {
            if (targetAccountId.equals(account.id) && account.type == AccountVault.Type.PASSWORD) return account;
        }
        return null;
    }

    private void refreshSessionStatusIfChanged() {
        if (busy) return;
        if (AccountRuntime.repository() != null) {
            showIdle();
            return;
        }
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
        AccountRepository repository = AccountRuntime.repository();
        if (repository != null && !repository.isOpen()) {
            status.setText(R.string.account_storage_error);
            return;
        }
        accountGeneration = repository == null ? -1 : repository.identityGeneration();
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
        boolean remember = rememberPassword.isChecked();
        password.setText("");
        long currentAttempt = attempt;
        status.setText(R.string.account_signing_in);
        worker.execute(() -> {
            try {
                NhentaiLoginApi.Challenge challenge = NhentaiLoginApi.challenge(getApplicationContext(), requestHandle);
                String nonce = PowSolver.solve(challenge.value, challenge.difficulty, 60000, cancelled::get);
                if (cancelled.get()) return;
                if (AccountRuntime.repository() == null) {
                    NhentaiLoginApi.login(getApplicationContext(), user, pass, captcha, challenge, nonce,
                        currentAttempt, requestHandle, userAgent, protectionCookies);
                } else {
                    NhentaiLoginApi.LoginResult result = NhentaiLoginApi.authenticate(getApplicationContext(),
                        user, pass, captcha, challenge, nonce, requestHandle, userAgent, protectionCookies);
                    if (cancelled.get() || !AuthSessionStore.completeAccountAttempt(result.cookies, currentAttempt,
                        accountGeneration, user, result.username == null ? user : result.username, result.avatarUrl,
                        remember ? pass : null, result.userId, targetAccountId))
                        throw new IOException("Login was cancelled or the account changed");
                }
                postIfActive(() -> {
                    Login.updateUser(null);
                    Login.isLogged(this);
                    if (AccountRuntime.repository() != null) {
                        finish();
                        return;
                    }
                    showIdle();
                });
            } catch (IOException | RuntimeException exception) {
                LogUtility.w("Account sign-in failed: " + exception.getClass().getSimpleName());
                int message = exception instanceof NhentaiLoginApi.ProtectionException
                    ? R.string.account_protection_error : exception instanceof AuthSessionStore.AccountMismatchException
                    ? R.string.account_identity_unconfirmed : R.string.account_login_error;
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
        rememberPassword.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this).setMessage(R.string.account_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.account_sign_out, (dialog, which) -> {
                AccountRepository repository = AccountRuntime.repository();
                if (repository != null) {
                    AccountVault.Account account = AccountRuntime.activeAccount();
                    if (account == null || account.type != AccountVault.Type.PASSWORD) return;
                    setBusy(true);
                    AccountRuntime.execute(store -> store.remove(account.id), error -> postIfActive(() -> {
                        Login.updateUser(null);
                        Login.isLogged(this);
                        showIdle();
                        if (error != null) status.setText(R.string.account_storage_error);
                    }));
                    return;
                }
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
        AuthStore.removeIdentityObserver(identityObserver);
        cancelled.set(true);
        if (requestHandle != null) requestHandle.cancel();
        AuthSessionStore.cancelAttempt(attempt);
        if (verification != null) verification.dismiss();
        worker.shutdownNow();
        super.onDestroy();
    }
}
