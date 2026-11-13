package com.maxwai.nclientv3.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates durable account changes and exposes a read-only in-memory snapshot. */
public final class AccountRepository {
    private final AccountVault vault;
    private final AtomicLong generation = new AtomicLong();
    @Nullable private volatile AccountVault.State state;

    public AccountRepository(@NonNull AccountVault vault) {
        this.vault = vault;
    }

    @WorkerThread
    public synchronized void open() throws IOException {
        if (state != null) return;
        beginChange();
        try {
            state = vault.load();
        } finally {
            endChange();
        }
    }

    public boolean isOpen() {
        return state != null;
    }

    @Nullable public AccountVault.State snapshot() {
        return state;
    }

    /** Returns -1 while the active identity is changing or the vault is unopened. */
    public long identityGeneration() {
        long value = generation.get();
        return state == null || (value & 1) != 0 ? -1 : value;
    }

    @WorkerThread
    public synchronized void select(@Nullable String accountId) throws IOException {
        AccountVault.State current = requireOpen();
        if (Objects.equals(current.activeId, accountId)) return;
        if (accountId != null && find(current.accounts, accountId) == null)
            throw new IOException("Account no longer exists");
        transition(ignored -> new AccountVault.State(ignored.accounts, accountId));
    }

    /** A completed login must still belong to the identity generation that started it. */
    @WorkerThread
    public synchronized boolean putAndSelect(@NonNull AccountVault.Account account, long expectedGeneration)
        throws IOException {
        requireOpen();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration()) return false;
        transition(current -> {
            List<AccountVault.Account> accounts = new ArrayList<>(current.accounts);
            int index = indexOf(accounts, account.id);
            if (index < 0) accounts.add(account);
            else accounts.set(index, account);
            return new AccountVault.State(accounts, account.id);
        });
        return true;
    }

    @WorkerThread
    public synchronized boolean putApiKeyAndSelect(@NonNull AccountVault.Account account,
                                                   @Nullable String targetId, long expectedGeneration)
        throws IOException {
        AccountVault.State current = requireOpen();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration()) return false;
        if (account.type != AccountVault.Type.API_KEY || account.userId <= 0 || account.rejected)
            throw new IOException("API key identity is unavailable");
        AccountVault.Account previous = find(current.accounts, targetId == null ? account.id : targetId);
        if (targetId != null && previous == null) throw new IOException("Account no longer exists");
        if (previous != null && (previous.type != AccountVault.Type.API_KEY || previous.userId <= 0
            || previous.userId != account.userId)) throw new AuthSessionStore.AccountMismatchException();
        AccountVault.Account collision = find(current.accounts, account.id);
        if (collision != null && (collision.type != AccountVault.Type.API_KEY || collision.userId != account.userId))
            throw new AuthSessionStore.AccountMismatchException();
        transition(value -> {
            List<AccountVault.Account> accounts = new ArrayList<>(value.accounts);
            if (targetId != null) accounts.removeIf(item -> targetId.equals(item.id));
            int index = indexOf(accounts, account.id);
            if (index < 0) accounts.add(account);
            else accounts.set(index, account);
            return new AccountVault.State(accounts, account.id);
        });
        return true;
    }

    @WorkerThread
    public synchronized void remove(@NonNull String accountId) throws IOException {
        AccountVault.State current = requireOpen();
        if (find(current.accounts, accountId) == null) return;
        transition(value -> {
            List<AccountVault.Account> accounts = new ArrayList<>(value.accounts);
            int index = indexOf(accounts, accountId);
            if (index >= 0) accounts.remove(index);
            return new AccountVault.State(accounts, Objects.equals(value.activeId, accountId) ? null : value.activeId);
        });
    }

    /** Discards a late refresh after a switch, including switching away and back. */
    @WorkerThread
    public synchronized boolean updateSession(@NonNull String accountId, long expectedGeneration,
                                              @NonNull String expectedRefreshToken,
                                              @NonNull String accessToken, @NonNull String refreshToken,
                                              long accessExpiry, long refreshExpiry) throws IOException {
        AccountVault.State current = requireOpen();
        AccountVault.Account account = current.activeAccount();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration() || account == null
            || !account.id.equals(accountId) || account.type != AccountVault.Type.PASSWORD
            || !expectedRefreshToken.equals(account.refreshToken)) return false;
        AccountVault.Account replacement = new AccountVault.Account(account.id, account.type, account.username,
            account.password, null, accessToken, refreshToken, accessExpiry, refreshExpiry, false, account.userId,
            account.loginName, account.avatarUrl);
        state = vault.update(value -> replace(value, replacement));
        return true;
    }

    @WorkerThread
    public synchronized boolean invalidateAccess(@NonNull String accountId, long expectedGeneration,
                                                 @NonNull String expectedToken) throws IOException {
        AccountVault.Account account = requireOpen().activeAccount();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration() || account == null
            || account.type != AccountVault.Type.PASSWORD || !accountId.equals(account.id)
            || !expectedToken.equals(account.accessToken)) return false;
        AccountVault.Account replacement = new AccountVault.Account(account.id, account.type, account.username,
            account.password, null, null, account.refreshToken, 0, account.refreshExpiry, account.rejected, account.userId,
            account.loginName, account.avatarUrl);
        state = vault.update(value -> replace(value, replacement));
        return true;
    }

    @WorkerThread
    public synchronized boolean reject(@NonNull String accountId, long expectedGeneration) throws IOException {
        AccountVault.State current = requireOpen();
        AccountVault.Account account = current.activeAccount();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration()
            || account == null || !account.id.equals(accountId)) return false;
        if (account.rejected) return true;
        AccountVault.Account rejected = new AccountVault.Account(account.id, account.type, account.username,
            account.password, account.apiKey, account.accessToken, account.refreshToken,
            account.accessExpiry, account.refreshExpiry, true, account.userId, account.loginName, account.avatarUrl);
        transition(value -> replace(value, rejected));
        return true;
    }

    @WorkerThread
    public synchronized boolean updateProfile(@NonNull String accountId, long expectedGeneration,
                                              long userId, @NonNull String username,
                                              @Nullable String avatarUrl) throws IOException {
        AccountVault.Account account = requireOpen().activeAccount();
        if (expectedGeneration < 0 || expectedGeneration != identityGeneration()
            || account == null || !account.id.equals(accountId) || userId <= 0) return false;
        if (account.userId > 0 && account.userId != userId) {
            reject(accountId, expectedGeneration);
            return false;
        }
        AccountVault.Account replacement = new AccountVault.Account(account.id, account.type, username,
            account.password, account.apiKey, account.accessToken, account.refreshToken,
            account.accessExpiry, account.refreshExpiry, account.rejected, userId, account.loginName, avatarUrl);
        state = vault.update(value -> replace(value, replacement));
        return true;
    }

    private void transition(AccountVault.Update update) throws IOException {
        beginChange();
        try {
            state = vault.update(update);
        } finally {
            endChange();
        }
    }

    private void beginChange() {
        AuthStore.beginIdentityChange();
        generation.incrementAndGet();
    }

    private void endChange() {
        generation.incrementAndGet();
        AuthStore.endIdentityChange();
    }

    @NonNull private AccountVault.State requireOpen() throws IOException {
        AccountVault.State current = state;
        if (current == null) throw new IOException("Account vault is unavailable");
        return current;
    }

    private static AccountVault.State replace(AccountVault.State state, AccountVault.Account account) throws IOException {
        List<AccountVault.Account> accounts = new ArrayList<>(state.accounts);
        int index = indexOf(accounts, account.id);
        if (index < 0) throw new IOException("Account no longer exists");
        accounts.set(index, account);
        return new AccountVault.State(accounts, state.activeId);
    }

    private static int indexOf(List<AccountVault.Account> accounts, String id) {
        for (int i = 0; i < accounts.size(); i++) if (accounts.get(i).id.equals(id)) return i;
        return -1;
    }

    @Nullable private static AccountVault.Account find(List<AccountVault.Account> accounts, String id) {
        int index = indexOf(accounts, id);
        return index < 0 ? null : accounts.get(index);
    }
}
