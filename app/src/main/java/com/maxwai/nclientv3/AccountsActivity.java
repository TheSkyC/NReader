package com.maxwai.nclientv3;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.RequestManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.loginapi.NhentaiLoginApi;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountVault;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Login;

import java.util.Objects;

public final class AccountsActivity extends GeneralActivity {
    private LinearLayout list;
    private TextView status;
    private MaterialButton add;
    private MaterialButton guest;
    private boolean busy;
    private final Runnable observer = this::render;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        list = findViewById(R.id.accounts_list);
        status = findViewById(R.id.accounts_status);
        add = findViewById(R.id.accounts_add);
        guest = findViewById(R.id.accounts_guest);
        add.setOnClickListener(view -> chooseMethod());
        guest.setOnClickListener(view -> select(null));
    }

    @Override protected void onStart() {
        super.onStart();
        AuthStore.observeIdentityChanges(observer);
        render();
    }

    @Override protected void onStop() {
        AuthStore.removeIdentityObserver(observer);
        super.onStop();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        list.removeAllViews();
        add.setEnabled(!busy && state != null);
        guest.setEnabled(!busy && state != null && state.activeId != null);
        if (state == null) {
            status.setText(AccountRuntime.openError() == null ? R.string.accounts_loading : R.string.account_storage_error);
            return;
        }
        status.setText(state.accounts.isEmpty() ? R.string.accounts_empty : R.string.accounts_shared_data);
        for (AccountVault.Account account : state.accounts) addAccountRow(account, state.activeId);
    }

    private void addAccountRow(AccountVault.Account account, @Nullable String activeId) {
        View row = getLayoutInflater().inflate(R.layout.item_account, list, false);
        TextView name = row.findViewById(R.id.account_name);
        TextView detail = row.findViewById(R.id.account_detail);
        ImageView avatar = row.findViewById(R.id.account_avatar);
        String displayName = account.username.isEmpty() ? getString(R.string.accounts_unnamed_key) : account.username;
        name.setText(displayName);
        String method = getString(account.type == AccountVault.Type.PASSWORD
            ? R.string.accounts_password_method : R.string.accounts_key_method);
        String state = null;
        if (account.rejected || (account.type == AccountVault.Type.PASSWORD
            && (account.refreshToken == null || account.refreshExpiry <= System.currentTimeMillis()))) {
            state = getString(account.type == AccountVault.Type.PASSWORD
                ? R.string.accounts_needs_sign_in : R.string.accounts_key_invalid);
        }
        if (Objects.equals(account.id, activeId)) state = state == null ? getString(R.string.accounts_current)
            : getString(R.string.accounts_detail_format, getString(R.string.accounts_current), state);
        detail.setText(state == null ? method : getString(R.string.accounts_detail_format, method, state));
        String avatarUrl = NhentaiLoginApi.accountAvatarUrl(account.avatarUrl, account.userId);
        RequestManager images = GlideX.with(avatar);
        if (images != null && avatarUrl != null)
            images.load(avatarUrl).placeholder(R.drawable.ic_person).error(R.drawable.ic_person).circleCrop().into(avatar);
        View select = row.findViewById(R.id.account_select);
        select.setEnabled(!busy);
        select.setOnClickListener(view -> select(account.id));
        View manage = row.findViewById(R.id.account_manage);
        manage.setEnabled(!busy);
        manage.setContentDescription(getString(R.string.accounts_manage_named, displayName));
        manage.setOnClickListener(view -> manage(account));
        list.addView(row);
    }

    private void chooseMethod() {
        String[] methods = {getString(R.string.accounts_password_method), getString(R.string.accounts_key_method)};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.accounts_choose_method)
            .setItems(methods, (dialog, which) -> {
                Intent intent = new Intent(this, which == 0 ? AccountLoginActivity.class : ApiKeyActivity.class);
                if (which == 1) intent.putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
                startActivity(intent);
            })
            .setNegativeButton(R.string.cancel, null).show();
    }

    private void manage(AccountVault.Account account) {
        String[] actions = {getString(account.type == AccountVault.Type.API_KEY
            ? R.string.accounts_revalidate_key : R.string.accounts_reauthenticate), getString(R.string.accounts_remove)};
        new MaterialAlertDialogBuilder(this).setTitle(account.username)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(this, account.type == AccountVault.Type.API_KEY
                        ? ApiKeyActivity.class : AccountLoginActivity.class);
                    intent.putExtra(account.type == AccountVault.Type.API_KEY
                        ? ApiKeyActivity.EXTRA_ACCOUNT_ID : AccountLoginActivity.EXTRA_ACCOUNT_ID, account.id);
                    startActivity(intent);
                }
                else confirmRemove(account);
            }).setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmRemove(AccountVault.Account account) {
        new MaterialAlertDialogBuilder(this).setMessage(R.string.account_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.accounts_remove, (dialog, which) -> run(store -> store.remove(account.id))).show();
    }

    private void select(@Nullable String accountId) {
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        if (state == null || Objects.equals(state.activeId, accountId)) return;
        run(store -> store.select(accountId));
    }

    private void run(AccountRuntime.Operation operation) {
        if (busy) return;
        busy = true;
        render();
        AccountRuntime.execute(operation, error -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            if (error != null) Toast.makeText(this, R.string.account_storage_error, Toast.LENGTH_LONG).show();
            else {
                Login.updateUser(null);
                Login.isLogged(this);
            }
            render();
        });
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
