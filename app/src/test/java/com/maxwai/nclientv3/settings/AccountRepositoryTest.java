package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class AccountRepositoryTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private AccountVault vault;
    private AccountRepository repository;

    @Before public void setUp() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        vault = new AccountVault(new File(folder.getRoot(), "vault"), () -> key);
        repository = new AccountRepository(vault);
        repository.open();
    }

    private AccountVault.Account account(String id) {
        return new AccountVault.Account(id, AccountVault.Type.PASSWORD, id, null, null,
            "access-" + id, "refresh-" + id, 100, 200, false, 0);
    }

    @Test public void switchAndRemoveSurviveReopenWithoutChangingOtherRecords() throws Exception {
        assertTrue(repository.putAndSelect(account("one"), repository.identityGeneration()));
        assertTrue(repository.putAndSelect(account("two"), repository.identityGeneration()));
        repository.select("one");
        AccountRepository reopened = new AccountRepository(vault);
        reopened.open();
        assertEquals("one", reopened.snapshot().activeId);
        assertEquals("refresh-one", reopened.snapshot().activeAccount().refreshToken);
        reopened.remove("one");
        assertNull(reopened.snapshot().activeId);
        assertEquals("two", reopened.snapshot().accounts.get(0).id);
        AccountRepository afterRemoval = new AccountRepository(vault);
        afterRemoval.open();
        assertNull(afterRemoval.snapshot().activeId);
        assertEquals("refresh-two", afterRemoval.snapshot().accounts.get(0).refreshToken);
    }

    @Test public void staleLoginAndRefreshCannotAffectAccountAfterSwitchAwayAndBack() throws Exception {
        assertTrue(repository.putAndSelect(account("one"), repository.identityGeneration()));
        assertTrue(repository.putAndSelect(account("two"), repository.identityGeneration()));
        repository.select("one");
        long oldGeneration = repository.identityGeneration();
        repository.select("two");
        repository.select("one");
        assertNotEquals(oldGeneration, repository.identityGeneration());
        assertFalse(repository.putAndSelect(account("late-login"), oldGeneration));
        assertFalse(repository.updateSession("one", oldGeneration, "refresh-one", "new-access", "new-refresh", 300, 400));
        assertFalse(repository.reject("one", oldGeneration));
        assertEquals("refresh-one", repository.snapshot().activeAccount().refreshToken);
        assertEquals(2, repository.snapshot().accounts.size());
        assertTrue(repository.updateSession("one", repository.identityGeneration(), "refresh-one",
            "new-access", "new-refresh", 300, 400));
        assertEquals("new-refresh", repository.snapshot().activeAccount().refreshToken);
    }

    @Test public void failedDiskWriteKeepsPreviouslyActiveAccount() throws Exception {
        assertTrue(repository.putAndSelect(account("one"), repository.identityGeneration()));
        assertTrue(repository.putAndSelect(account("two"), repository.identityGeneration()));
        java.nio.file.Files.write(new File(folder.getRoot(), "vault").toPath(), new byte[]{1, 2, 3});
        long generation = repository.identityGeneration();
        try {
            repository.select("one");
            org.junit.Assert.fail("Unreadable persistent state must fail closed");
        } catch (IOException expected) {
            assertEquals("two", repository.snapshot().activeId);
            assertNotEquals(generation, repository.identityGeneration());
        }
    }

    private AccountVault.Account apiKey(String key, long userId) {
        return new AccountVault.Account(ApiAuthInterceptor.fingerprint(key), AccountVault.Type.API_KEY,
            "Verified", null, key, null, null, 0, 0, false, userId);
    }

    @Test public void rotatedKeyReplacesOnlyTheSameConfirmedUserAndSurvivesReopen() throws Exception {
        AccountVault.Account original = apiKey("old-key", 42);
        repository.putAndSelect(account("other"), repository.identityGeneration());
        repository.putAndSelect(original, repository.identityGeneration());
        AccountVault.Account replacement = apiKey("new-key", 42);
        assertTrue(repository.putApiKeyAndSelect(replacement, original.id, repository.identityGeneration()));
        assertEquals(2, repository.snapshot().accounts.size());
        assertEquals(replacement.id, repository.snapshot().activeId);
        AccountRepository reopened = new AccountRepository(vault);
        reopened.open();
        assertEquals("new-key", reopened.snapshot().activeAccount().apiKey);
        assertEquals("other", reopened.snapshot().accounts.get(0).id);
    }

    @Test public void revalidationCannotActivateKeyForAnotherOrUnconfirmedUser() throws Exception {
        AccountVault.Account original = apiKey("old-key", 42);
        repository.putAndSelect(original, repository.identityGeneration());
        repository.reject(original.id, repository.identityGeneration());
        try {
            repository.putApiKeyAndSelect(apiKey("old-key", 43), original.id, repository.identityGeneration());
            org.junit.Assert.fail("A different user must not activate the stored key");
        } catch (AuthSessionStore.AccountMismatchException expected) {
            assertTrue(repository.snapshot().activeAccount().rejected);
            assertEquals(42, repository.snapshot().activeAccount().userId);
        }
        AccountVault.Account unknown = apiKey("unknown-key", 0);
        repository.putAndSelect(unknown, repository.identityGeneration());
        try {
            repository.putApiKeyAndSelect(apiKey("replacement-key", 42), unknown.id, repository.identityGeneration());
            org.junit.Assert.fail("An unconfirmed account must not be rebound");
        } catch (AuthSessionStore.AccountMismatchException expected) {
            assertEquals(unknown.id, repository.snapshot().activeId);
        }
    }

    @Test public void lateKeyValidationCannotOverwriteAChangedSelection() throws Exception {
        repository.putAndSelect(account("other"), repository.identityGeneration());
        long generation = repository.identityGeneration();
        repository.select(null);
        assertFalse(repository.putApiKeyAndSelect(apiKey("new-key", 42), null, generation));
        assertNull(repository.snapshot().activeId);
        assertEquals(1, repository.snapshot().accounts.size());
    }

    @Test public void profileCannotChangeAnEstablishedUserIdentity() throws Exception {
        assertTrue(repository.putAndSelect(account("one"), repository.identityGeneration()));
        assertTrue(repository.updateProfile("one", repository.identityGeneration(), 42, "First", null));
        long generation = repository.identityGeneration();
        assertTrue(repository.updateProfile("one", generation, 42, "Updated", null));
        assertFalse(repository.updateProfile("one", generation, 43, "Other", null));
        assertTrue(repository.snapshot().activeAccount().rejected);
        assertEquals(42, repository.snapshot().activeAccount().userId);
        assertEquals("Updated", repository.snapshot().activeAccount().username);
        assertNotEquals(generation, repository.identityGeneration());
        AccountRepository reopened = new AccountRepository(vault);
        reopened.open();
        assertTrue(reopened.snapshot().activeAccount().rejected);
        assertEquals(42, reopened.snapshot().activeAccount().userId);
    }
}
