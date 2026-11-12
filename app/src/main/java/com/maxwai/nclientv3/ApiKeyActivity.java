package com.maxwai.nclientv3;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountVault;
import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class ApiKeyActivity extends GeneralActivity {
    private static final long MAX_VALIDATION_RESPONSE = 2L * 1024 * 1024;
    public static final String EXTRA_ADD_ACCOUNT = "add_account";
    public static final String EXTRA_ACCOUNT_ID = "account_id";

    private TextView statusText;
    private LinearLayout inputGroup;
    private EditText apiKeyInput;
    private ProgressBar progressBar;
    private MaterialButton openApiKeyPage;
    private MaterialButton clearApiKey;
    private MaterialButton validateApiKey;
    private boolean validationInFlight = false;
    private boolean addAccount;
    @Nullable private String targetAccountId;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Call> validationCall = new AtomicReference<>();
    private final AtomicInteger validationState = new AtomicInteger();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_key);
        addAccount = getIntent().getBooleanExtra(EXTRA_ADD_ACCOUNT, false);
        targetAccountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(R.string.title_activity_api_key);
        assert getSupportActionBar() != null;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(true);

        statusText = findViewById(R.id.api_key_status_text);
        inputGroup = findViewById(R.id.api_key_input_group);
        apiKeyInput = findViewById(R.id.api_key_input);
        progressBar = findViewById(R.id.login_progress);
        openApiKeyPage = findViewById(R.id.open_api_key_page);
        clearApiKey = findViewById(R.id.clear_api_key);
        validateApiKey = findViewById(R.id.validate_api_key);

        setInputVisible(true);
        openApiKeyPage.setOnClickListener(v -> openApiKeyPage());
        clearApiKey.setOnClickListener(v -> clearSavedApiKey());
        validateApiKey.setOnClickListener(v -> validateAndSaveApiKey());
        loadSavedApiKey();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSavedApiKey();
    }

    private void openApiKeyPage() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Utility.getBaseUrl() + "user/settings#apikeys")));
    }

    private void updateStatusMessage(@NonNull String message) {
        statusText.setText(message);
    }

    private void setInputVisible(boolean visible) {
        inputGroup.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setLoading(boolean loading) {
        validationInFlight = loading;
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        apiKeyInput.setEnabled(!loading);
        openApiKeyPage.setEnabled(!loading);
        clearApiKey.setEnabled(!loading && AuthStore.hasApiKey(this));
        validateApiKey.setEnabled(!loading);
        updateStatusMessage(getStatusMessage());
    }

    private void loadSavedApiKey() {
        AccountVault.Account target = targetAccount();
        String apiKey = targetAccountId != null ? (target == null ? null : target.apiKey)
            : addAccount ? null : AuthStore.getApiKey(this);
        if (apiKey != null && !validationInFlight) {
            apiKeyInput.setText(apiKey);
        }
        clearApiKey.setEnabled(!addAccount && !validationInFlight && AuthStore.hasApiKey(this));
        clearApiKey.setVisibility(addAccount || targetAccountId != null ? View.GONE : View.VISIBLE);
        updateStatusMessage(getStatusMessage());
    }

    @NonNull
    private String getStatusMessage() {
        if (targetAccountId != null) {
            AccountVault.Account account = targetAccount();
            if (account == null) return getString(R.string.account_storage_error);
            return getString(account.rejected ? R.string.login_status_api_key_invalid : R.string.login_status_api_key_saved);
        }
        if (addAccount) return getString(R.string.login_api_key_intro_message);
        if (AuthStore.hasValidApiKey(this)) return getString(R.string.login_status_api_key_saved);
        if (AuthStore.hasApiKey(this)) return getString(R.string.login_status_api_key_invalid);
        return getString(R.string.login_api_key_intro_message);
    }

    @Nullable
    private AccountVault.Account targetAccount() {
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.State state = repository == null ? null : repository.snapshot();
        if (targetAccountId == null || state == null) return null;
        for (AccountVault.Account account : state.accounts) {
            if (targetAccountId.equals(account.id) && account.type == AccountVault.Type.API_KEY) return account;
        }
        return null;
    }

    private void clearSavedApiKey() {
        AccountRepository repository = AccountRuntime.repository();
        if (repository != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            if (account == null || account.type != AccountVault.Type.API_KEY) return;
            setLoading(true);
            AccountRuntime.execute(store -> store.remove(account.id), error -> {
                if (isFinishing() || isDestroyed()) return;
                setLoading(false);
                if (error != null) {
                    Toast.makeText(this, R.string.account_storage_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                Login.updateUser(null);
                apiKeyInput.setText("");
                loadSavedApiKey();
                Toast.makeText(this, R.string.login_api_key_removed, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        AuthStore.clear(this);
        Login.updateUser(null);
        apiKeyInput.setText("");
        clearApiKey.setEnabled(false);
        updateStatusMessage(getStatusMessage());
        Toast.makeText(this, R.string.login_api_key_removed, Toast.LENGTH_SHORT).show();
    }

    private void validateAndSaveApiKey() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.login_api_key_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String candidateId = ApiAuthInterceptor.fingerprint(apiKey);
        if (targetAccountId != null && !targetAccountId.equals(candidateId)) {
            Toast.makeText(this, R.string.accounts_key_mismatch, Toast.LENGTH_LONG).show();
            return;
        }

        setLoading(true);
        AccountRepository repository = AccountRuntime.repository();
        long generation = repository == null ? -1 : repository.identityGeneration();
        if (repository != null && generation < 0) {
            setLoading(false);
            Toast.makeText(this, R.string.account_storage_error, Toast.LENGTH_SHORT).show();
            return;
        }
        Request request = new Request.Builder()
            .url(Utility.getApiBaseUrl() + "favorites")
            .header("Authorization", "Key " + apiKey)
            .build();
        Call pending = Global.getClient(this).newCall(request);
        validationState.set(1);
        validationCall.set(pending);
        pending.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                validationCall.compareAndSet(call, null);
                validationState.compareAndSet(1, 0);
                if (stopped.get()) return;
                LogUtility.e("API key validation failed", e);
                runOnUiThread(() -> {
                    if (stopped.get() || isFinishing() || isDestroyed()) return;
                    setLoading(false);
                    Toast.makeText(ApiKeyActivity.this, R.string.unable_to_connect_to_the_site, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    validationCall.compareAndSet(call, null);
                    if (stopped.get()) return;
                    if (response.isSuccessful() && isValidFavoritesResponse(response)) {
                        if (!validationState.compareAndSet(1, 2)) return;
                        if (repository == null) {
                            AuthStore.saveApiKey(ApiKeyActivity.this, apiKey, true);
                        } else {
                            try {
                                AccountVault.Account previous = null;
                                AccountVault.State state = repository.snapshot();
                                if (state == null) throw new IOException("Account storage is unavailable");
                                for (AccountVault.Account item : state.accounts) {
                                    if (candidateId.equals(item.id) && item.type == AccountVault.Type.API_KEY) previous = item;
                                }
                                if (targetAccountId != null && previous == null) throw new IOException("Account no longer exists");
                                AccountVault.Account account = new AccountVault.Account(candidateId, AccountVault.Type.API_KEY,
                                    previous == null ? "" : previous.username, null, apiKey, null, null, 0, 0,
                                    false, previous == null ? 0 : previous.userId, null,
                                    previous == null ? null : previous.avatarUrl);
                                if (stopped.get()) return;
                                if (!repository.putAndSelect(account, generation)) throw new IOException("Account changed");
                            } catch (IOException | IllegalArgumentException exception) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    setLoading(false);
                                    Toast.makeText(ApiKeyActivity.this, R.string.account_storage_error, Toast.LENGTH_SHORT).show();
                                });
                                return;
                            }
                        }
                        Login.updateUser(null);
                        Login.isLogged(ApiKeyActivity.this);
                        runOnUiThread(() -> {
                            if (stopped.get() || isFinishing() || isDestroyed()) return;
                            setLoading(false);
                            Toast.makeText(ApiKeyActivity.this, R.string.login_api_key_saved, Toast.LENGTH_SHORT).show();
                            finish();
                        });
                        return;
                    }

                    LogUtility.w("API key validation rejected: " + response.code());
                    validationState.compareAndSet(1, 0);
                    int errorMessage = response.code() == 401 || response.code() == 403
                        ? R.string.login_api_key_invalid : R.string.unable_to_connect_to_the_site;
                    runOnUiThread(() -> {
                        if (stopped.get() || isFinishing() || isDestroyed()) return;
                        setLoading(false);
                        Toast.makeText(ApiKeyActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private static boolean isValidFavoritesResponse(@NonNull Response response) {
        try {
            okhttp3.ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_VALIDATION_RESPONSE
                || body.source().request(MAX_VALIDATION_RESPONSE + 1)) return false;
            JSONObject result = new JSONObject(body.source().readUtf8());
            Object pages = result.opt("num_pages");
            return result.optJSONArray("result") != null
                && (pages instanceof Integer || pages instanceof Long) && ((Number) pages).longValue() >= 0;
        } catch (IOException | JSONException exception) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        stopped.set(true);
        validationState.compareAndSet(1, 3);
        Call call = validationCall.getAndSet(null);
        if (call != null) call.cancel();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) finish();
        return super.onOptionsItemSelected(item);
    }
}
