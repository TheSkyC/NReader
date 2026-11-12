package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Application-wide access to the account repository; disk work runs on a dedicated worker. */
public final class AccountRuntime {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private static volatile AccountRepository repository;
    @Nullable private static volatile IOException openError;
    @Nullable private static volatile CountDownLatch initialization;

    private AccountRuntime() { }

    public static synchronized void initialize(@NonNull Context context) {
        Context application = context.getApplicationContext();
        if (repository != null) return;
        AccountRepository created = new AccountRepository(new AccountVault(application));
        CountDownLatch ready = new CountDownLatch(1);
        initialization = ready;
        openError = null;
        repository = created;
        AuthStore.beginIdentityChange();
        worker.execute(() -> {
            try {
                created.open();
            } catch (IOException exception) {
                openError = exception;
                LogUtility.w("Unable to open account storage: " + exception.getClass().getSimpleName());
            } catch (RuntimeException exception) {
                openError = new IOException("Unable to open account storage", exception);
                LogUtility.w("Unable to open account storage: " + exception.getClass().getSimpleName());
            } finally {
                AuthStore.endIdentityChange();
                ready.countDown();
            }
        });
    }

    static void awaitInitialization() throws IOException {
        CountDownLatch ready = initialization;
        if (ready == null) return;
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("Account storage is still loading");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading account storage", exception);
        }
    }

    @Nullable public static AccountRepository repository() {
        return repository;
    }

    @Nullable public static IOException openError() {
        return openError;
    }

    @Nullable public static AccountVault.Account activeAccount() {
        AccountRepository current = repository;
        AccountVault.State state = current == null ? null : current.snapshot();
        return state == null ? null : state.activeAccount();
    }

    public interface Operation {
        void run(@NonNull AccountRepository repository) throws IOException;
    }

    public interface Completion {
        void complete(@Nullable IOException error);
    }

    public static void execute(@NonNull Operation operation, @NonNull Completion completion) {
        worker.execute(() -> {
            IOException failure = null;
            try {
                AccountRepository current = repository;
                if (current == null || !current.isOpen()) throw new IOException("Account storage is unavailable", openError);
                operation.run(current);
            } catch (IOException exception) {
                failure = exception;
            } catch (RuntimeException exception) {
                failure = new IOException("Account operation failed", exception);
            }
            IOException result = failure;
            main.post(() -> completion.complete(result));
        });
    }
}
