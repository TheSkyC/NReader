package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test public void selectingAccountsAndGuestUpdatesTheList() throws Exception {
        repository.putAndSelect(passwordAccount("first", true), repository.identityGeneration());
        repository.putAndSelect(passwordAccount("second", true), repository.identityGeneration());
        try (ActivityController<AccountsActivity> controller = Robolectric.buildActivity(AccountsActivity.class).setup()) {
            AccountsActivity activity = controller.get();
            LinearLayout list = activity.findViewById(R.id.accounts_list);
            list.getChildAt(0).findViewById(R.id.account_select).performClick();
            awaitAccountOperations();
            assertEquals("first", repository.snapshot().activeId);
            String firstDetail = ((TextView) list.getChildAt(0).findViewById(R.id.account_detail)).getText().toString();
            assertTrue(firstDetail.contains(activity.getString(R.string.accounts_current)));

            activity.findViewById(R.id.accounts_guest).performClick();
            awaitAccountOperations();
            assertNull(repository.snapshot().activeId);
            assertEquals(2, list.getChildCount());
            assertFalse(activity.findViewById(R.id.accounts_guest).isEnabled());

            list.getChildAt(1).findViewById(R.id.account_select).performClick();
            awaitAccountOperations();
            assertEquals("second", repository.snapshot().activeId);
            String secondDetail = ((TextView) list.getChildAt(1).findViewById(R.id.account_detail)).getText().toString();
            assertTrue(secondDetail.contains(activity.getString(R.string.accounts_current)));
        }
    }

    private void awaitAccountOperations() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AccountRuntime.execute(store -> completed.countDown(), error -> { });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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

    @Test public void leavingApiKeyPageCancelsPendingValidation() throws Exception {
        String originalKey = "original-key-fixture";
        String originalId = ApiAuthInterceptor.fingerprint(originalKey);
        repository.putAndSelect(new AccountVault.Account(originalId, AccountVault.Type.API_KEY, "Original",
            null, originalKey, null, null, 0, 0, false, 1), repository.identityGeneration());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        okhttp3.OkHttpClient originalClient = Global.client;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Timed out");
                return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create("", null)).build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            } finally {
                returned.countDown();
            }
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
            ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup();
            ((EditText) controller.get().findViewById(R.id.api_key_input)).setText("new-key-fixture");
            controller.get().findViewById(R.id.validate_api_key).performClick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.pause().stop().destroy();
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, client.dispatcher().runningCallsCount());
            assertEquals(originalId, repository.snapshot().activeId);
            assertEquals(1, repository.snapshot().accounts.size());
        } finally {
            release.countDown();
            Global.client = originalClient;
        }
    }
}
