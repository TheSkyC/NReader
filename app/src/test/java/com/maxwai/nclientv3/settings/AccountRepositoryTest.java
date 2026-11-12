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
@Config(sdk = 28)
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
}
