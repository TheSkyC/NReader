package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AccountAuthenticationIntegrationTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private AccountRepository repository;
    private Application application;
    private Field runtimeRepository;
    private OkHttpClient oldClient;

    @Before public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        oldClient = Global.client;
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
        Global.client = oldClient;
    }

    private AccountVault.Account session(String id, String access, String refresh) {
        long expiry = System.currentTimeMillis() + 600000;
        return new AccountVault.Account(id, AccountVault.Type.PASSWORD, id, "password-fixture", null,
            access, refresh, expiry, expiry, false, 5, "login-fixture", null);
    }

    @Test public void selectedAccountControlsExistingAuthenticationFacade() throws Exception {
        String keyId = ApiAuthInterceptor.fingerprint("key-fixture");
        AccountVault.Account key = new AccountVault.Account(keyId, AccountVault.Type.API_KEY, "key account",
            null, "key-fixture", null, null, 0, 0, false, 0);
        assertTrue(repository.putAndSelect(key, repository.identityGeneration()));
        assertEquals(keyId, AuthStore.activeIdentity(application));
        assertEquals("key-fixture", AuthStore.getApiKey(application));
        assertTrue(repository.putAndSelect(session("session", "access", "refresh"), repository.identityGeneration()));
        assertEquals("session", AuthStore.activeIdentity(application));
        assertNull(AuthStore.getApiKey(application));
        assertEquals("access", AuthSessionStore.accessToken(application));
        repository.select(keyId);
        assertNull(AuthSessionStore.identity(application));
        assertEquals(keyId, AuthStore.activeIdentity(application));
        repository.select(null);
        assertFalse(AuthStore.hasOnlineIdentity(application));
    }

    @Test public void successfulLoginStoresOnlyOptedInPasswordAndKeepsOtherAccounts() throws Exception {
        long generation = repository.identityGeneration();
        long attempt = AuthSessionStore.beginAttempt();
        assertTrue(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("access-one", "refresh-one"),
            attempt, generation, "login-one", "One", null, "remembered-fixture", 1, null));
        String firstId = repository.snapshot().activeId;
        assertEquals("remembered-fixture", repository.snapshot().activeAccount().password);
        generation = repository.identityGeneration();
        attempt = AuthSessionStore.beginAttempt();
        assertTrue(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("access-two", "refresh-two"),
            attempt, generation, "login-two", "Two", null, null, 2, null));
        assertNull(repository.snapshot().activeAccount().password);
        assertEquals(2, repository.snapshot().accounts.size());
        repository.select(firstId);
        assertEquals("remembered-fixture", repository.snapshot().activeAccount().password);
        assertEquals("login-one", repository.snapshot().activeAccount().loginName);
    }

    @Test public void loginStartedBeforeSwitchCannotReplaceSelectedAccount() throws Exception {
        assertTrue(repository.putAndSelect(session("first-login", "one", "refresh-one"), repository.identityGeneration()));
        assertTrue(repository.putAndSelect(session("second-login", "two", "refresh-two"), repository.identityGeneration()));
        repository.select("first-login");
        long generation = repository.identityGeneration();
        long attempt = AuthSessionStore.beginAttempt();
        repository.select("second-login");
        assertFalse(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("late", "late-refresh"),
            attempt, generation, "new-login", "New", null, "password", 10, null));
        assertEquals("second-login", repository.snapshot().activeId);
        assertEquals(2, repository.snapshot().accounts.size());
    }

    @Test public void reauthenticationRejectsDifferentUserAndCanForgetPreviouslySavedPassword() throws Exception {
        long attempt = AuthSessionStore.beginAttempt();
        assertTrue(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("old", "old-refresh"),
            attempt, repository.identityGeneration(), "login", "User", null, "password-fixture", 42, null));
        String accountId = repository.snapshot().activeId;
        attempt = AuthSessionStore.beginAttempt();
        long wrongAttempt = attempt;
        org.junit.Assert.assertThrows(java.io.IOException.class, () ->
            AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("wrong", "wrong-refresh"),
                wrongAttempt, repository.identityGeneration(), "other", "Other", null, null, 43, accountId));
        assertEquals("old-refresh", repository.snapshot().activeAccount().refreshToken);
        assertEquals("password-fixture", repository.snapshot().activeAccount().password);
        attempt = AuthSessionStore.beginAttempt();
        assertTrue(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("new", "new-refresh"),
            attempt, repository.identityGeneration(), "login", "User", null, null, 42, accountId));
        assertEquals(accountId, repository.snapshot().activeId);
        assertEquals("new-refresh", repository.snapshot().activeAccount().refreshToken);
        assertNull(repository.snapshot().activeAccount().password);
        assertEquals(1, repository.snapshot().accounts.size());
    }

    @Test public void refreshPersistsOnlyToTheAccountThatStartedIt() throws Exception {
        assertTrue(repository.putAndSelect(session("refresh-first", "old-access", "old-refresh"), repository.identityGeneration()));
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}", null)).build()).build();
        assertEquals("new-access", SessionRefresh.access(application, "refresh-first", "old-access"));
        assertEquals("new-refresh", repository.snapshot().activeAccount().refreshToken);
        assertEquals("password-fixture", repository.snapshot().activeAccount().password);
        assertEquals("login-fixture", repository.snapshot().activeAccount().loginName);
    }

    @Test public void refreshResponseAfterSwitchAwayAndBackIsDiscarded() throws Exception {
        assertTrue(repository.putAndSelect(session("first", "old-access", "old-refresh"), repository.identityGeneration()));
        assertTrue(repository.putAndSelect(session("second", "other-access", "other-refresh"), repository.identityGeneration()));
        repository.select("first");
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            repository.select("second");
            repository.select("first");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create("{\"access_token\":\"late-access\",\"refresh_token\":\"late-refresh\"}", null)).build();
        }).build();
        assertNull(SessionRefresh.access(application, "first", "old-access"));
        assertEquals("old-refresh", repository.snapshot().activeAccount().refreshToken);
        repository.select("second");
        assertEquals("other-refresh", repository.snapshot().activeAccount().refreshToken);
    }
}
