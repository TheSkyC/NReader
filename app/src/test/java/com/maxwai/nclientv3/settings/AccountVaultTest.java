package com.maxwai.nclientv3.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AccountVaultTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private File path;
    private SecretKey key;
    private AccountVault vault;

    @Before public void setup() throws Exception {
        path = new File(folder.getRoot(), "vault");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
        vault = new AccountVault(path, () -> key);
    }

    private AccountVault.Account passwordAccount(String id, String password) {
        return new AccountVault.Account(id, AccountVault.Type.PASSWORD, "test-user", password, null,
            "test-access-token", "test-refresh-token", 100, 200, false, 123);
    }

    @Test public void encryptedRecordsRoundTripWithOptionalPasswordAndActiveAccount() throws Exception {
        AccountVault.Account first = passwordAccount("first", "test-secret-password");
        AccountVault.Account second = new AccountVault.Account("second", AccountVault.Type.API_KEY,
            "key-user", null, "test-secret-api-key", null, null, 0, 0, false, 456);
        vault.update(current -> new AccountVault.State(Arrays.asList(first, second), "second"));
        String bytes = new String(Files.readAllBytes(path.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("test-secret-password"));
        assertFalse(bytes.contains("test-secret-api-key"));
        assertFalse(bytes.contains("test-refresh-token"));
        AccountVault.State restored = new AccountVault(path, () -> key).load();
        assertEquals(2, restored.accounts.size());
        assertEquals("test-secret-password", restored.accounts.get(0).password);
        assertEquals("test-secret-api-key", restored.activeAccount().apiKey);
        assertEquals(456, restored.activeAccount().userId);
        vault.update(current -> new AccountVault.State(Collections.singletonList(passwordAccount("first", null)), "first"));
        assertNull(vault.load().activeAccount().password);
    }

    @Test public void randomNonceAndAuthenticatedCiphertextRejectTamperingWithoutDeletingFile() throws Exception {
        AccountVault.State state = new AccountVault.State(Collections.singletonList(passwordAccount("first", null)), "first");
        vault.update(current -> state);
        byte[] first = Files.readAllBytes(path.toPath());
        vault.update(current -> state);
        byte[] second = Files.readAllBytes(path.toPath());
        assertFalse(Arrays.equals(first, second));
        second[second.length - 1] ^= 1;
        Files.write(path.toPath(), second);
        try {
            vault.load();
            fail("Modified ciphertext must be rejected");
        } catch (IOException expected) {
            assertTrue(Arrays.equals(second, Files.readAllBytes(path.toPath())));
        }
    }

    @Test public void unavailableKeyFailsWithoutOverwritingExistingVault() throws Exception {
        vault.update(current -> new AccountVault.State(Collections.singletonList(passwordAccount("first", null)), "first"));
        byte[] original = Files.readAllBytes(path.toPath());
        AccountVault broken = new AccountVault(path, () -> { throw new IOException("Key unavailable"); });
        try {
            broken.update(current -> new AccountVault.State(Collections.emptyList(), null));
            fail("An unreadable vault must not be replaced");
        } catch (IOException expected) {
            assertTrue(Arrays.equals(original, Files.readAllBytes(path.toPath())));
        }
    }

    @Test public void concurrentUpdatesDoNotLoseAccounts() throws Exception {
        int count = 12;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[count];
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < count; i++) {
            String id = "account-" + i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    vault.update(current -> {
                        ArrayList<AccountVault.Account> accounts = new ArrayList<>(current.accounts);
                        accounts.add(passwordAccount(id, null));
                        return new AccountVault.State(accounts, current.activeId);
                    });
                } catch (Throwable failure) {
                    error.compareAndSet(null, failure);
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(10));
        for (Thread thread : threads) assertFalse(thread.isAlive());
        assertNull(error.get());
        assertEquals(count, vault.load().accounts.size());
    }

    @Test public void invalidActiveAndDuplicateIdsAreRejected() {
        AccountVault.Account account = passwordAccount("first", null);
        try {
            new AccountVault.State(Collections.singletonList(account), "missing");
            fail("Unknown active account must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            new AccountVault.State(Arrays.asList(account, account), "first");
            fail("Duplicate account IDs must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
