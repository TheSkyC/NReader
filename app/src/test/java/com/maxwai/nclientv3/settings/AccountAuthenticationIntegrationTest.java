package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.Queries;

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
import java.util.Collections;

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

    @Test public void reauthenticationRequiresVerifiedUserIds() throws Exception {
        long attempt = AuthSessionStore.beginAttempt();
        assertTrue(AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("old", "old-refresh"),
            attempt, repository.identityGeneration(), "login", "User", null, null, 0, null));
        String accountId = repository.snapshot().activeId;
        long nextAttempt = AuthSessionStore.beginAttempt();
        org.junit.Assert.assertThrows(java.io.IOException.class, () ->
            AuthSessionStore.completeAccountAttempt(AuthSessionStore.tokenCookies("other", "other-refresh"),
                nextAttempt, repository.identityGeneration(), "other", "Other", null, null, 42, accountId));
        assertEquals("old-refresh", repository.snapshot().activeAccount().refreshToken);
        assertEquals(accountId, repository.snapshot().activeId);
    }

    @Test public void favoriteCacheRemainsScopedWhenSwitchingSavedAccounts() throws Exception {
        String firstKey = "favorites-first-fixture";
        String secondKey = "favorites-second-fixture";
        String firstId = ApiAuthInterceptor.fingerprint(firstKey);
        String secondId = ApiAuthInterceptor.fingerprint(secondKey);
        assertTrue(repository.putAndSelect(new AccountVault.Account(firstId, AccountVault.Type.API_KEY,
            "First", null, firstKey, null, null, 0, 0, false, 1), repository.identityGeneration()));
        com.maxwai.nclientv3.api.OnlineFavoriteClient.rememberState(application, 987654, true);
        assertEquals(Boolean.TRUE, com.maxwai.nclientv3.api.OnlineFavoriteClient.cachedState(application, 987654));
        assertTrue(repository.putAndSelect(new AccountVault.Account(secondId, AccountVault.Type.API_KEY,
            "Second", null, secondKey, null, null, 0, 0, false, 2), repository.identityGeneration()));
        assertNull(com.maxwai.nclientv3.api.OnlineFavoriteClient.cachedState(application, 987654));
        com.maxwai.nclientv3.api.OnlineFavoriteClient.rememberState(application, 987654, false);
        assertEquals(Boolean.FALSE, com.maxwai.nclientv3.api.OnlineFavoriteClient.cachedState(application, 987654));
        repository.select(firstId);
        assertFalse(Boolean.FALSE.equals(com.maxwai.nclientv3.api.OnlineFavoriteClient.cachedState(application, 987654)));
        repository.select(null);
        assertNull(com.maxwai.nclientv3.api.OnlineFavoriteClient.cachedState(application, 987654));
    }

    @Test public void accountSwitchIsolatesOnlineTagsAndPreservesLocalFilters() throws Exception {
        Global.initFromShared(application);
        Login.initLogin(application);
        String firstKey = "tags-first-fixture";
        String secondKey = "tags-second-fixture";
        String firstId = ApiAuthInterceptor.fingerprint(firstKey);
        String secondId = ApiAuthInterceptor.fingerprint(secondKey);
        Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
        schema.setAccessible(true);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL((String) schema.get(null));
            Tag local = new Tag("local", 1, 1001, TagType.TAG, TagStatus.AVOIDED);
            Tag first = new Tag("first", 1, 1002, TagType.TAG, TagStatus.DEFAULT);
            Tag second = new Tag("second", 1, 1003, TagType.TAG, TagStatus.DEFAULT);
            Queries.TagTable.insert(local);
            assertTrue(repository.putAndSelect(new AccountVault.Account(firstId, AccountVault.Type.API_KEY,
                "First", null, firstKey, null, null, 0, 0, false, 1), repository.identityGeneration()));
            long firstLoad = Login.beginOnlineTagsLoad(application, firstId);
            assertTrue(Login.replaceOnlineTags(application, firstId, firstLoad, Collections.singletonList(first)));
            long staleLoad = Login.beginOnlineTagsLoad(application, firstId);
            assertEquals(first.getId(), Queries.TagTable.getAllOnlineBlacklisted().get(0).getId());

            assertTrue(repository.putAndSelect(new AccountVault.Account(secondId, AccountVault.Type.API_KEY,
                "Second", null, secondKey, null, null, 0, 0, false, 2), repository.identityGeneration()));
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            assertFalse(Login.replaceOnlineTags(application, firstId, staleLoad, Collections.singletonList(first)));
            long secondLoad = Login.beginOnlineTagsLoad(application, secondId);
            assertTrue(Login.replaceOnlineTags(application, secondId, secondLoad, Collections.singletonList(second)));
            assertEquals(second.getId(), Queries.TagTable.getAllOnlineBlacklisted().get(0).getId());

            repository.select(firstId);
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            assertFalse(Login.replaceOnlineTags(application, firstId, staleLoad, Collections.singletonList(first)));
            assertEquals(TagStatus.AVOIDED, Queries.TagTable.getTagById(local.getId()).getStatus());
            repository.select(null);
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            assertEquals(TagStatus.AVOIDED, Queries.TagTable.getTagById(local.getId()).getStatus());
        } finally {
            Queries.setDb(null);
            Login.updateUser(null);
        }
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

    @Test public void mismatchedProfileRejectsAccountWithoutInstallingWrongUser() throws Exception {
        Global.initFromShared(application);
        Login.initLogin(application);
        Login.updateUser(null);
        assertTrue(repository.putAndSelect(session("profile", "access", "refresh"), repository.identityGeneration()));
        java.util.concurrent.CountDownLatch reached = new java.util.concurrent.CountDownLatch(1);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            reached.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("{\"id\":6,\"username\":\"wrong-user\"}", null)).build();
        }).build();
        Global.client = client;
        com.maxwai.nclientv3.loginapi.User.createUser(application, null);
        assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (client.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(0, client.dispatcher().runningCallsCount());
        assertTrue(repository.snapshot().activeAccount().rejected);
        assertEquals(5, repository.snapshot().activeAccount().userId);
        assertNull(Login.getUser());
        assertNull(AuthStore.activeIdentity(application));
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
