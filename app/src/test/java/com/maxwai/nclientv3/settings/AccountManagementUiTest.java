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
import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.database.DatabaseHelper;

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

    @Test public void lateVaultLoadDoesNotOverwriteEditedCredentials() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Field vaultField = AccountRepository.class.getDeclaredField("vault");
        vaultField.setAccessible(true);
        AccountRepository loading = new AccountRepository((AccountVault) vaultField.get(repository));
        runtimeRepository.set(null, loading);
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            AccountLoginActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.account_username)).setText("edited-login");
            ((EditText) activity.findViewById(R.id.account_password)).setText("edited-password");
            loading.open();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("edited-login", ((EditText) activity.findViewById(R.id.account_username)).getText().toString());
            assertEquals("edited-password", ((EditText) activity.findViewById(R.id.account_password)).getText().toString());
            assertFalse(((CheckBox) activity.findViewById(R.id.account_remember_password)).isChecked());
        }
    }

    @Test public void lateVaultLoadPrefillsUntouchedForm() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Field vaultField = AccountRepository.class.getDeclaredField("vault");
        vaultField.setAccessible(true);
        AccountRepository loading = new AccountRepository((AccountVault) vaultField.get(repository));
        runtimeRepository.set(null, loading);
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            loading.open();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("login-name", ((EditText) controller.get().findViewById(R.id.account_username)).getText().toString());
            assertEquals("fixture-password", ((EditText) controller.get().findViewById(R.id.account_password)).getText().toString());
            assertTrue(((CheckBox) controller.get().findViewById(R.id.account_remember_password)).isChecked());
        }
    }

    @Test public void vaultLoadingAcrossRecreationStillPrefillsUntouchedForm() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Field vaultField = AccountRepository.class.getDeclaredField("vault");
        vaultField.setAccessible(true);
        AccountRepository loading = new AccountRepository((AccountVault) vaultField.get(repository));
        runtimeRepository.set(null, loading);
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            controller.recreate();
            loading.open();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("login-name", ((EditText) controller.get().findViewById(R.id.account_username)).getText().toString());
            assertEquals("fixture-password", ((EditText) controller.get().findViewById(R.id.account_password)).getText().toString());
            assertTrue(((CheckBox) controller.get().findViewById(R.id.account_remember_password)).isChecked());
        }
    }

    @Test public void editedFormIsNotPrefilledAfterRecreationDuringVaultLoad() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Field vaultField = AccountRepository.class.getDeclaredField("vault");
        vaultField.setAccessible(true);
        AccountRepository loading = new AccountRepository((AccountVault) vaultField.get(repository));
        runtimeRepository.set(null, loading);
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            ((EditText) controller.get().findViewById(R.id.account_username)).setText("edited-login");
            ((EditText) controller.get().findViewById(R.id.account_password)).setText("unsaved-password");
            controller.recreate();
            loading.open();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("edited-login", ((EditText) controller.get().findViewById(R.id.account_username)).getText().toString());
            assertEquals("", ((EditText) controller.get().findViewById(R.id.account_password)).getText().toString());
            assertFalse(((CheckBox) controller.get().findViewById(R.id.account_remember_password)).isChecked());
        }
    }

    @Test public void recreatedReauthenticationDoesNotReplaceEditedPasswordWithSavedPassword() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            AccountLoginActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.account_username)).setText("different-login");
            ((EditText) activity.findViewById(R.id.account_password)).setText("unsaved-password");
            ((CheckBox) activity.findViewById(R.id.account_remember_password)).setChecked(false);
            controller.recreate();
            activity = controller.get();
            assertEquals("different-login", ((EditText) activity.findViewById(R.id.account_username)).getText().toString());
            assertEquals("", ((EditText) activity.findViewById(R.id.account_password)).getText().toString());
            assertFalse(((CheckBox) activity.findViewById(R.id.account_remember_password)).isChecked());
        }
    }

    @Test public void recreatedReauthenticationRetainsAnUnchangedRememberedPassword() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            controller.recreate();
            assertEquals("fixture-password", ((EditText) controller.get().findViewById(R.id.account_password)).getText().toString());
        }
    }

    @Test public void recreatedReauthenticationDoesNotPairSavedPasswordWithEditedUsername() throws Exception {
        repository.putAndSelect(passwordAccount("first", false), repository.identityGeneration());
        Intent intent = new Intent(application, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, "first");
        try (ActivityController<AccountLoginActivity> controller = Robolectric.buildActivity(AccountLoginActivity.class, intent).setup()) {
            ((EditText) controller.get().findViewById(R.id.account_username)).setText("different-login");
            controller.recreate();
            assertEquals("different-login", ((EditText) controller.get().findViewById(R.id.account_username)).getText().toString());
            assertEquals("", ((EditText) controller.get().findViewById(R.id.account_password)).getText().toString());
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

    @Test public void profileLoadRetriesWhenReturningAfterFailure() throws Exception {
        String key = "profile-key-fixture";
        String id = ApiAuthInterceptor.fingerprint(key);
        repository.putAndSelect(new AccountVault.Account(id, AccountVault.Type.API_KEY, "", null, key,
            null, null, 0, 0, false, 0), repository.identityGeneration());
        Login.updateUser(null);
        okhttp3.OkHttpClient originalClient = Global.client;
        java.util.concurrent.atomic.AtomicInteger profileCalls = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch retried = new CountDownLatch(1);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            boolean profile = chain.request().url().encodedPath().endsWith("/user");
            int call = profile ? profileCalls.incrementAndGet() : 0;
            if (call == 1) failed.countDown();
            if (call == 2) retried.countDown();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(call == 1 ? 503 : 200).message("fixture")
                .body(okhttp3.ResponseBody.create(profile ? "{\"id\":42,\"username\":\"Profile\"}"
                    : "{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        Global.client = client;
        Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean originalFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(failed.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                controller.pause().stop().restart().start().resume().visible();
                assertTrue(retried.await(5, TimeUnit.SECONDS));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!"Profile".equals(repository.snapshot().activeAccount().username) && System.nanoTime() < deadline)
                    Thread.yield();
                assertEquals("Profile", repository.snapshot().activeAccount().username);
                controller.pause().stop().restart().start().resume().visible();
                assertEquals(2, profileCalls.get());
            }
        } finally {
            firstTime.setBoolean(null, originalFirstTime);
            Global.client = originalClient;
            Login.updateUser(null);
        }
    }

    @Test public void drawerReflectsAccountSwitchAndGuestMode() throws Exception {
        repository.putAndSelect(passwordAccount("first", true), repository.identityGeneration());
        long expiry = System.currentTimeMillis() + 600000;
        repository.putAndSelect(new AccountVault.Account("second", AccountVault.Type.PASSWORD, "Second user",
            null, null, "access-two", "refresh-two", expiry, expiry, true, 8), repository.identityGeneration());
        Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean originalFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        okhttp3.OkHttpClient originalClient = Global.client;
        Global.client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain ->
            new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503).message("fixture").body(okhttp3.ResponseBody.create("{}", null)).build()).build();
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                TextView name = activity.findViewById(R.id.nav_account_name);
                TextView action = activity.findViewById(R.id.nav_account_action);
                assertEquals("Second user", name.getText().toString());
                assertEquals(activity.getString(R.string.accounts_needs_sign_in), action.getText().toString());
                repository.select("first");
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Display name", name.getText().toString());
                repository.select(null);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(activity.getString(R.string.accounts_guest), name.getText().toString());
                assertEquals(activity.getString(R.string.accounts_switch), action.getText().toString());
                com.google.android.material.navigation.NavigationView navigation = activity.findViewById(R.id.nav_view);
                navigation.getHeaderView(0).performClick();
                assertEquals(AccountsActivity.class.getName(), Shadows.shadowOf(activity).getNextStartedActivity()
                    .getComponent().getClassName());
            }
        } finally {
            Global.client = originalClient;
            firstTime.setBoolean(null, originalFirstTime);
            Login.updateUser(null);
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

    @Test public void returningToKeyRevalidationPreservesEditedKeyAndDoesNotRestoreItAfterRecreation() throws Exception {
        String savedKey = "saved-key-fixture";
        String id = ApiAuthInterceptor.fingerprint(savedKey);
        repository.putAndSelect(new AccountVault.Account(id, AccountVault.Type.API_KEY, "Existing",
            null, savedKey, null, null, 0, 0, false, 10), repository.identityGeneration());
        Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ACCOUNT_ID, id);
        try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
            EditText input = controller.get().findViewById(R.id.api_key_input);
            assertEquals(savedKey, input.getText().toString());
            input.setText("replacement-key-fixture");
            controller.pause().stop().restart().start().resume();
            assertEquals("replacement-key-fixture", input.getText().toString());
            controller.recreate();
            assertEquals("", ((EditText) controller.get().findViewById(R.id.api_key_input)).getText().toString());
        }
    }

    @Test public void validatedApiKeyIsAddedAndSelectedWithoutRemovingExistingAccount() throws Exception {
        repository.putAndSelect(passwordAccount("existing", true), repository.identityGeneration());
        String newKey = "validated-key-fixture";
        okhttp3.OkHttpClient originalClient = Global.client;
        CountDownLatch validated = new CountDownLatch(1);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            boolean validation = chain.request().url().encodedPath().endsWith("/user");
            if (validation) {
                assertEquals("Key " + newKey, chain.request().header("Authorization"));
                validated.countDown();
            }
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(validation ? 200 : 503).message("fixture")
                .body(okhttp3.ResponseBody.create(validation ? "{\"id\":42,\"username\":\"Verified\"}" : "{}", null)).build();
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
            try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
                ((EditText) controller.get().findViewById(R.id.api_key_input)).setText(newKey);
                controller.get().findViewById(R.id.validate_api_key).performClick();
                assertTrue(validated.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                String newId = ApiAuthInterceptor.fingerprint(newKey);
                while (!newId.equals(repository.snapshot().activeId) && System.nanoTime() < deadline) Thread.yield();
                assertEquals(newId, repository.snapshot().activeId);
                assertEquals(2, repository.snapshot().accounts.size());
                assertEquals(newKey, repository.snapshot().activeAccount().apiKey);
                assertEquals(42, repository.snapshot().activeAccount().userId);
                assertEquals("Verified", repository.snapshot().activeAccount().username);
                assertEquals("existing", repository.snapshot().accounts.get(0).id);
                while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, client.dispatcher().runningCallsCount());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(controller.get().isFinishing());
            }
        } finally {
            Global.client = originalClient;
            Login.updateUser(null);
        }
    }

    @Test public void apiKeyRevalidationWaitsForMatchingProfileBeforeEnablingAccount() throws Exception {
        String key = "rejected-key-fixture";
        String id = ApiAuthInterceptor.fingerprint(key);
        repository.putAndSelect(new AccountVault.Account(id, AccountVault.Type.API_KEY, "Original",
            null, key, null, null, 0, 0, true, 42), repository.identityGeneration());
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        okhttp3.OkHttpClient originalClient = Global.client;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            requested.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(okhttp3.ResponseBody.create("{\"id\":43,\"username\":\"Other\"}", null)).build();
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ACCOUNT_ID, id);
            try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
                controller.get().findViewById(R.id.validate_api_key).performClick();
                assertTrue(requested.await(5, TimeUnit.SECONDS));
                assertTrue(repository.snapshot().activeAccount().rejected);
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, client.dispatcher().runningCallsCount());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(repository.snapshot().activeAccount().rejected);
                assertEquals(42, repository.snapshot().activeAccount().userId);
                assertTrue(controller.get().findViewById(R.id.validate_api_key).isEnabled());
                assertFalse(controller.get().isFinishing());
            }
        } finally {
            release.countDown();
            Global.client = originalClient;
        }
    }

    @Test public void rejectedApiKeyDoesNotChangeExistingAccountAndCanBeRetried() throws Exception {
        repository.putAndSelect(passwordAccount("existing", true), repository.identityGeneration());
        okhttp3.OkHttpClient originalClient = Global.client;
        CountDownLatch validated = new CountDownLatch(1);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            validated.countDown();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(401).message("fixture").body(okhttp3.ResponseBody.create("{}", null)).build();
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
            try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
                ApiKeyActivity activity = controller.get();
                ((EditText) activity.findViewById(R.id.api_key_input)).setText("invalid-key-fixture");
                activity.findViewById(R.id.validate_api_key).performClick();
                assertTrue(validated.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, client.dispatcher().runningCallsCount());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("existing", repository.snapshot().activeId);
                assertEquals(1, repository.snapshot().accounts.size());
                assertEquals("fixture-password", repository.snapshot().activeAccount().password);
                assertTrue(activity.findViewById(R.id.validate_api_key).isEnabled());
                assertFalse(activity.isFinishing());
            }
        } finally {
            Global.client = originalClient;
        }
    }

    @Test public void invalidSuccessfulApiResponseDoesNotAddAccount() throws Exception {
        repository.putAndSelect(passwordAccount("existing", true), repository.identityGeneration());
        okhttp3.OkHttpClient originalClient = Global.client;
        CountDownLatch received = new CountDownLatch(1);
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            received.countDown();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(okhttp3.ResponseBody.create("<html>Login</html>", null)).build();
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
            try (ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup()) {
                ((EditText) controller.get().findViewById(R.id.api_key_input)).setText("invalid-response-key");
                controller.get().findViewById(R.id.validate_api_key).performClick();
                assertTrue(received.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, client.dispatcher().runningCallsCount());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("existing", repository.snapshot().activeId);
                assertEquals(1, repository.snapshot().accounts.size());
                assertTrue(controller.get().findViewById(R.id.validate_api_key).isEnabled());
            }
        } finally {
            Global.client = originalClient;
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

    @Test public void leavingApiKeyPageCancelsValidationWhileReadingResponse() throws Exception {
        repository.putAndSelect(passwordAccount("existing", true), repository.identityGeneration());
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<okhttp3.Call> pending = new java.util.concurrent.atomic.AtomicReference<>();
        okhttp3.OkHttpClient originalClient = Global.client;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            pending.set(chain.call());
            okio.BufferedSource source = okio.Okio.buffer(new okio.ForwardingSource(
                new okio.Buffer().writeUtf8("{\"id\":42,\"username\":\"Verified\"}")) {
                @Override public long read(okio.Buffer sink, long count) throws java.io.IOException {
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(exception);
                    }
                    return super.read(sink, count);
                }
            });
            okhttp3.ResponseBody body = new okhttp3.ResponseBody() {
                @Override public okhttp3.MediaType contentType() { return null; }
                @Override public long contentLength() { return -1; }
                @Override public okio.BufferedSource source() { return source; }
            };
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body).build();
        }).build();
        Global.client = client;
        try {
            Intent intent = new Intent(application, ApiKeyActivity.class).putExtra(ApiKeyActivity.EXTRA_ADD_ACCOUNT, true);
            ActivityController<ApiKeyActivity> controller = Robolectric.buildActivity(ApiKeyActivity.class, intent).setup();
            ((EditText) controller.get().findViewById(R.id.api_key_input)).setText("new-key-fixture");
            controller.get().findViewById(R.id.validate_api_key).performClick();
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            controller.pause().stop().destroy();
            assertTrue(pending.get().isCanceled());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, client.dispatcher().runningCallsCount());
            assertEquals("existing", repository.snapshot().activeId);
            assertEquals(1, repository.snapshot().accounts.size());
        } finally {
            release.countDown();
            Global.client = originalClient;
        }
    }
}
