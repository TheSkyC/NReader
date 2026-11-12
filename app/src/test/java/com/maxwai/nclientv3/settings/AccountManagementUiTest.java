package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maxwai.nclientv3.AccountLoginActivity;
import com.maxwai.nclientv3.AccountsActivity;
import com.maxwai.nclientv3.ApiKeyActivity;
import com.maxwai.nclientv3.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AccountManagementUiTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private Application application;
    private AccountRepository repository;
    private Field runtimeRepository;

    @Before public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        repository = new AccountRepository(new AccountVault(new File(folder.getRoot(), "vault"), () -> key));
        repository.open();
        runtimeRepository = AccountRuntime.class.getDeclaredField("repository");
        runtimeRepository.setAccessible(true);
        runtimeRepository.set(null, repository);
    }

    @After public void tearDown() throws Exception {
        runtimeRepository.set(null, null);
    }

    private AccountVault.Account passwordAccount(String id, boolean rejected) {
        long expiry = System.currentTimeMillis() + 600000;
        return new AccountVault.Account(id, AccountVault.Type.PASSWORD, "Display name", "fixture-password", null,
            "access", "refresh", expiry, expiry, rejected, 7, "login-name", null);
    }

    @Test public void selectedExpiredAccountShowsBothCurrentAndRecoveryStates() throws Exception {
        repository.putAndSelect(passwordAccount("first", true), repository.identityGeneration());
        try (ActivityController<AccountsActivity> controller = Robolectric.buildActivity(AccountsActivity.class).setup()) {
            AccountsActivity activity = controller.get();
            LinearLayout list = activity.findViewById(R.id.accounts_list);
            assertEquals(1, list.getChildCount());
            View row = list.getChildAt(0);
            assertEquals("Display name", ((TextView) row.findViewById(R.id.account_name)).getText().toString());
            String detail = ((TextView) row.findViewById(R.id.account_detail)).getText().toString();
            assertTrue(detail.contains(activity.getString(R.string.accounts_current)));
            assertTrue(detail.contains(activity.getString(R.string.accounts_needs_sign_in)));
        }
    }

    @Test public void reauthenticationPrefillsOnlyTheSelectedAccount() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            AccountLoginActivity activity = controller.get();
            assertEquals("login-name", ((EditText) activity.findViewById(R.id.account_username)).getText().toString());
            assertEquals("fixture-password", ((EditText) activity.findViewById(R.id.account_password)).getText().toString());
            assertTrue(((CheckBox) activity.findViewById(R.id.account_remember_password)).isChecked());
            assertEquals(View.GONE, activity.findViewById(R.id.account_sign_out).getVisibility());
        }
    }

    @Test public void addingApiKeyDoesNotPrefillOrOfferToRemoveExistingKey() throws Exception {
        String key = "existing-key-fixture";
        repository.putAndSelect(new AccountVault.Account(ApiAuthInterceptor.fingerprint(key), AccountVault.Type.API_KEY,
            "Existing", null, key, null, null, 0, 0, false, 10), repository.identityGeneration());
        Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
        try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
            ApiKeyActivity activity = controller.get();
            assertEquals("", ((EditText) activity.findViewById(R.id.api_key_input)).getText().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.clear_api_key).getVisibility());
        }
    }

    @Test public void unsavedApiKeyIsNotRestoredAfterRecreation() {
        Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
        try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
            ((EditText) controller.get().findViewById(R.id.api_key_input)).setText("unsaved-key-fixture");
            controller.recreate();
            assertEquals("", ((EditText) controller.get().findViewById(R.id.api_key_input)).getText().toString());
        }
    }
}
