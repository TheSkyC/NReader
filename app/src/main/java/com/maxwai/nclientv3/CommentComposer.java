package com.maxwai.nclientv3;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.api.comments.CommentMutationClient;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.components.views.TurnstileDialog;
import com.maxwai.nclientv3.loginapi.NhentaiLoginApi;
import com.maxwai.nclientv3.loginapi.PowSolver;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountVault;
import com.maxwai.nclientv3.settings.AuthStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommentComposer {
    static final int LOGIN_REQUEST = 7931;
    private static final String PREFERENCES = "CommentIdentity";
    private static final String ACCOUNT = "account";
    private static final String DEFAULT_CHECKED = "default_checked";
    private static final String BROWSING_KNOWN = "browsing_known";
    private static final String BROWSING_ACCOUNT = "browsing_account";
    private final GalleryActivity activity;
    private final int galleryId;
    private final Runnable refresh;
    private final Button identity;
    private final Button send;
    private final EditText input;
    private final TextView status;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Bundle drafts;
    private final Bundle uncertain;
    private final Runnable observer;
    @Nullable private volatile String selected;
    @Nullable private String reauthenticate;
    private boolean forceSelection;
    @Nullable private Dialog verification;
    @Nullable private NhentaiLoginApi.CancelHandle verificationRequest;
    @Nullable private CommentMutationClient mutation;
    private volatile AtomicBoolean cancelled = new AtomicBoolean();
    private boolean busy;
    private volatile boolean destroyed;
    private boolean changingText;
    private boolean dispatched;
    @Nullable private String operationAccount;
    private long operationRevision;
    @Nullable private String browsingAccount;
    private boolean browsingKnown;
    private volatile long attempt;

    CommentComposer(GalleryActivity activity, int galleryId, @Nullable Bundle state, Runnable refresh) {
        this.activity = activity;
        observer = () -> activity.runOnUiThread(this::render);
        this.galleryId = galleryId;
        this.refresh = refresh;
        identity = activity.findViewById(R.id.comment_identity);
        send = activity.findViewById(R.id.comment_send);
        input = activity.findViewById(R.id.comment_input);
        status = activity.findViewById(R.id.comment_status);
        preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        browsingKnown = preferences.getBoolean(BROWSING_KNOWN, false);
        browsingAccount = preferences.getString(BROWSING_ACCOUNT, null);
        selected = state == null ? preferences.getString(ACCOUNT, null) : state.getString("commentAccount");
        reauthenticate = state == null ? null : state.getString("commentReauthenticate");
        forceSelection = state != null && state.getBoolean("commentForceSelection");
        Bundle savedDrafts = state == null ? null : state.getBundle("commentDrafts");
        drafts = savedDrafts == null ? new Bundle() : savedDrafts;
        Bundle savedUncertain = state == null ? null : state.getBundle("commentUncertain");
        uncertain = savedUncertain == null ? new Bundle() : savedUncertain;
        if (state != null && state.getBoolean("commentDispatched")) {
            String pendingAccount = state.getString("commentOperationAccount");
            if (pendingAccount != null) uncertain.putBoolean(pendingAccount, true);
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!changingText && selected != null) drafts.putString(selected, s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        identity.setOnClickListener(view -> chooseAccount());
        send.setOnClickListener(view -> begin());
        showDraft();
        AuthStore.observeIdentityChanges(observer);
        render();
    }

    void save(Bundle out) {
        out.putString("commentAccount", selected);
        out.putString("commentReauthenticate", reauthenticate);
        out.putBoolean("commentForceSelection", forceSelection);
        out.putBundle("commentDrafts", new Bundle(drafts));
        out.putBundle("commentUncertain", new Bundle(uncertain));
        out.putBoolean("commentDispatched", dispatched);
        out.putString("commentOperationAccount", operationAccount);
        if (busy) {
            boolean wasDispatched = dispatched;
            attempt++;
            cancelled.set(true);
            if (verificationRequest != null) verificationRequest.cancel();
            if (mutation != null) mutation.cancel();
            if (verification != null) verification.dismiss();
            busy = false;
            dispatched = false;
            if (wasDispatched) {
                if (operationAccount != null) uncertain.putBoolean(operationAccount, true);
                showStatus(R.string.comment_unknown);
            }
            render();
        }
    }

    void destroy() {
        destroyed = true;
        attempt++;
        cancelled.set(true);
        if (verificationRequest != null) verificationRequest.cancel();
        if (mutation != null) mutation.cancel();
        if (verification != null) verification.dismiss();
        AuthStore.removeIdentityObserver(observer);
        worker.shutdownNow();
    }

    void onLoginResult(int result, @Nullable Intent data) {
        if (result == Activity.RESULT_OK && data != null) {
            String id = data.getStringExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID);
            if (id != null) {
                reauthenticate = null;
                select(id);
            }
        }
        if (forceSelection && selectedAccount() != null) showStatus(R.string.comment_choose_after_sign_in);
        render();
    }

    @Nullable private AccountVault.Account selectedAccount() {
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.Account account = repository == null || selected == null ? null : repository.account(selected);
        return account != null && account.type == AccountVault.Type.PASSWORD ? account : null;
    }

    private boolean usable(@Nullable AccountVault.Account account) {
        return account != null && !account.rejected && account.refreshToken != null
            && account.refreshExpiry > System.currentTimeMillis();
    }

    void render() {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()) return;
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.Account account = selectedAccount();
        if (repository != null && repository.isOpen()) {
            String active = repository.snapshot().activeId;
            if (browsingKnown && browsingAccount != null && active == null) {
                selected = null;
                forceSelection = true;
                account = null;
                preferences.edit().remove(ACCOUNT).putBoolean(DEFAULT_CHECKED, true).apply();
                cancelForIdentityChange();
                showDraft();
            }
            browsingAccount = active;
            browsingKnown = true;
            preferences.edit().putBoolean(BROWSING_KNOWN, true).putString(BROWSING_ACCOUNT, active).apply();
            if (busy && operationAccount != null
                && repository.accountRevision(operationAccount) != operationRevision) cancelForIdentityChange();
        }
        if (selected != null && account == null && repository != null && repository.isOpen()) {
            selected = null;
            forceSelection = true;
            preferences.edit().remove(ACCOUNT).putBoolean(DEFAULT_CHECKED, true).apply();
            cancelForIdentityChange();
            showDraft();
        }
        if (selected == null && repository != null && repository.isOpen()
            && !preferences.getBoolean(DEFAULT_CHECKED, false)) {
            AccountVault.Account active = repository.snapshot().activeAccount();
            preferences.edit().putBoolean(DEFAULT_CHECKED, true).apply();
            if (usable(active) && active.type == AccountVault.Type.PASSWORD) {
                selected = active.id;
                preferences.edit().putString(ACCOUNT, selected).apply();
                showDraft();
                account = active;
            }
        }
        boolean available = usable(account) && !account.id.equals(reauthenticate);
        identity.setText(account == null ? activity.getString(R.string.comment_choose_account)
            : activity.getString(available ? R.string.comment_as : R.string.comment_expired, account.username));
        identity.setEnabled(!busy && repository != null && repository.isOpen());
        input.setVisibility(available ? View.VISIBLE : View.GONE);
        send.setVisibility(available ? View.VISIBLE : View.GONE);
        input.setEnabled(!busy);
        send.setEnabled(!busy);
        activity.updateCommentActions();
    }

    private void cancelForIdentityChange() {
        if (!busy) return;
        attempt++;
        cancelled.set(true);
        if (verificationRequest != null) verificationRequest.cancel();
        if (mutation != null) mutation.cancel();
        if (verification != null) verification.dismiss();
        if (dispatched && operationAccount != null) uncertain.putBoolean(operationAccount, true);
        showStatus(dispatched ? R.string.comment_unknown : R.string.comment_session_expired);
        busy = false;
        dispatched = false;
    }

    private void showDraft() {
        changingText = true;
        input.setText(selected == null ? "" : drafts.getString(selected, ""));
        changingText = false;
        if (selected != null && uncertain.getBoolean(selected, false)) showStatus(R.string.comment_unknown);
    }

    private void select(String accountId) {
        selected = accountId;
        forceSelection = false;
        preferences.edit().putString(ACCOUNT, accountId).putBoolean(DEFAULT_CHECKED, true).apply();
        showDraft();
        if (!uncertain.getBoolean(accountId, false)) status.setVisibility(View.GONE);
        render();
    }

    long currentUserId() {
        AccountVault.Account account = selectedAccount();
        return usable(account) && !account.id.equals(reauthenticate) ? account.userId : -1;
    }

    void delete(Comment comment) {
        AccountVault.Account account = selectedAccount();
        AccountRepository repository = AccountRuntime.repository();
        if (busy || !usable(account) || repository == null || account.userId != comment.getPosterId()) return;
        String accountId = account.id;
        long revision = repository.accountRevision(accountId);
        new MaterialAlertDialogBuilder(activity).setMessage(R.string.comment_delete_confirm)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.comment_delete, (dialog, which) -> {
                long currentAttempt = ++attempt;
                cancelled = new AtomicBoolean();
                if (!isCurrent(currentAttempt, accountId, revision)) return;
                busy = true;
                operationAccount = accountId;
                operationRevision = revision;
                dispatched = true;
                showStatus(R.string.comment_deleting);
                render();
                mutation = new CommentMutationClient(activity, accountId, revision,
                    () -> isCurrent(currentAttempt, accountId, revision));
                CommentMutationClient client = mutation;
                worker.execute(() -> {
                    try {
                        CommentMutationClient.Result result = client.delete(comment.getId());
                        post(currentAttempt, () -> {
                            if (result.status == 401) reauthenticate = accountId;
                            finishAttempt(currentAttempt, result.confirmed ? R.string.comment_deleted
                                : errorForStatus(result.status));
                            if (result.confirmed) refresh.run();
                        });
                    } catch (IOException | RuntimeException exception) {
                        post(currentAttempt, () -> finishAttempt(currentAttempt, R.string.comment_unknown));
                    }
                });
            }).show();
    }

    private void chooseAccount() {
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        if (state == null) return;
        List<AccountVault.Account> accounts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (AccountVault.Account account : state.accounts) {
            if (account.type != AccountVault.Type.PASSWORD) continue;
            accounts.add(account);
            labels.add(usable(account) && !account.id.equals(reauthenticate) ? account.username
                : activity.getString(R.string.comment_expired, account.username));
        }
        if (accounts.isEmpty()) {
            new MaterialAlertDialogBuilder(activity).setTitle(R.string.comment_login_title)
                .setMessage(R.string.comment_login_message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.comment_login_action, (dialog, which) -> login(null)).show();
            return;
        }
        labels.add(activity.getString(R.string.comment_add_account));
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.comment_select_title)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which == accounts.size()) login(null);
                else if (usable(accounts.get(which)) && !accounts.get(which).id.equals(reauthenticate)) select(accounts.get(which).id);
                else login(accounts.get(which).id);
            }).setNegativeButton(R.string.cancel, null).show();
    }

    private void login(@Nullable String id) {
        Intent intent = new Intent(activity, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_COMMENT_ACCOUNT, true);
        if (id != null) intent.putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, id);
        forceSelection = true;
        activity.startActivityForResult(intent, LOGIN_REQUEST);
    }

    private void begin() {
        if (forceSelection) {
            showStatus(R.string.comment_choose_after_sign_in);
            chooseAccount();
            return;
        }
        if (selected != null && uncertain.getBoolean(selected, false)) {
            new MaterialAlertDialogBuilder(activity).setMessage(R.string.comment_unknown_confirm)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.comment_send_again, (dialog, which) -> {
                    uncertain.remove(selected);
                    begin();
                }).show();
            return;
        }
        AccountVault.Account account = selectedAccount();
        AccountRepository repository = AccountRuntime.repository();
        if (!usable(account) || repository == null || busy) { render(); return; }
        String text = input.getText().toString();
        int length = text.codePointCount(0, text.length());
        if (text.trim().isEmpty() || length < 10 || length > 1000) {
            input.setError(activity.getString(R.string.comment_length_error));
            return;
        }
        String accountId = account.id;
        long revision = repository.accountRevision(accountId);
        long currentAttempt = ++attempt;
        cancelled = new AtomicBoolean();
        AtomicBoolean cancelFlag = cancelled;
        verificationRequest = new NhentaiLoginApi.CancelHandle();
        NhentaiLoginApi.CancelHandle handle = verificationRequest;
        busy = true;
        operationAccount = accountId;
        operationRevision = revision;
        dispatched = false;
        showStatus(R.string.comment_preparing);
        render();
        worker.execute(() -> {
            try {
                NhentaiLoginApi.Challenge challenge = NhentaiLoginApi.commentChallenge(activity.getApplicationContext(), handle);
                String nonce = challenge.difficulty == 0 ? "" : PowSolver.solve(challenge.value, challenge.difficulty, 60000, cancelFlag::get);
                String key = NhentaiLoginApi.captchaSiteKey(activity.getApplicationContext(), handle);
                post(currentAttempt, () -> verification = TurnstileDialog.show(activity, key, R.string.comment_verify_title, new TurnstileDialog.Callback() {
                    @Override public void onToken(@NonNull String token, @NonNull String userAgent, @NonNull String cookies) {
                        postComment(currentAttempt, accountId, revision, text, challenge.value, nonce, token, userAgent, cookies);
                    }
                    @Override public void onCancelled() { finishAttempt(currentAttempt, 0); }
                    @Override public void onFailure() { finishAttempt(currentAttempt, R.string.comment_verification_failed); }
                }));
            } catch (IOException | RuntimeException exception) {
                post(currentAttempt, () -> finishAttempt(currentAttempt, R.string.comment_verification_failed));
            }
        });
    }

    private void postComment(long currentAttempt, String accountId, long revision, String text,
                             String challenge, String nonce, String token, String agent, String cookies) {
        if (!isCurrent(currentAttempt, accountId, revision)) { finishAttempt(currentAttempt, R.string.comment_session_expired); return; }
        showStatus(R.string.comment_posting);
        dispatched = true;
        mutation = new CommentMutationClient(activity, accountId, revision,
            () -> isCurrent(currentAttempt, accountId, revision));
        CommentMutationClient client = mutation;
        worker.execute(() -> {
            try {
                CommentMutationClient.Result result = client.publish(galleryId, text, token, challenge, nonce, agent, cookies);
                post(currentAttempt, () -> {
                    if (result.confirmed) {
                        if (text.equals(drafts.getString(accountId))) {
                            drafts.remove(accountId);
                            if (accountId.equals(selected)) showDraft();
                        }
                        finishAttempt(currentAttempt, R.string.comment_posted);
                        refresh.run();
                    } else {
                        if (result.status == 401) reauthenticate = accountId;
                        finishAttempt(currentAttempt, errorForStatus(result.status));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                post(currentAttempt, () -> finishAttempt(currentAttempt, R.string.comment_unknown));
            }
        });
    }

    private boolean isCurrent(long currentAttempt, String accountId, long revision) {
        AccountRepository repository = AccountRuntime.repository();
        return !destroyed && !cancelled.get() && attempt == currentAttempt && accountId.equals(selected)
            && repository != null && repository.accountRevision(accountId) == revision;
    }

    private int errorForStatus(int code) {
        if (code == 401) return R.string.comment_session_expired;
        if (code == 429) return R.string.comment_rate_limited;
        if (code == 503) return R.string.comment_disabled;
        if (code == 403) return R.string.comment_verification_failed;
        if (code >= 500) return R.string.comment_unknown;
        return R.string.comment_rejected;
    }

    private void post(long currentAttempt, Runnable task) {
        activity.runOnUiThread(() -> { if (!destroyed && currentAttempt == attempt) task.run(); });
    }

    private void finishAttempt(long currentAttempt, int message) {
        if (destroyed || currentAttempt != attempt) return;
        busy = false;
        dispatched = false;
        verification = null;
        mutation = null;
        if (selected != null && message == R.string.comment_unknown) uncertain.putBoolean(selected, true);
        if (message == 0) status.setVisibility(View.GONE); else showStatus(message);
        render();
    }

    private void showStatus(int message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }
}
