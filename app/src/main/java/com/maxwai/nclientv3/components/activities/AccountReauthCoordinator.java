package com.maxwai.nclientv3.components.activities;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.AccountLoginActivity;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountVault;
import com.maxwai.nclientv3.settings.AuthStore;

/** Starts one foreground reauthentication flow for an expired saved-password account. */
public final class AccountReauthCoordinator {
    private static final Object LOCK = new Object();
    private static String launchedAccountId;
    private static long launchedGeneration = Long.MIN_VALUE;

    private AccountReauthCoordinator() {
    }

    public static boolean maybeLaunch(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || activity instanceof AccountLoginActivity) return false;
        AccountRepository repository = AccountRuntime.repository();
        AccountVault.Account account = AccountRuntime.activeAccount();
        long generation = AuthStore.identityGeneration();
        if (repository == null || !repository.isOpen() || account == null || generation < 0
            || !canAutoReauthenticate(account, System.currentTimeMillis())) return false;
        synchronized (LOCK) {
            if (account.id.equals(launchedAccountId) && generation == launchedGeneration) return false;
            launchedAccountId = account.id;
            launchedGeneration = generation;
        }
        Intent intent = new Intent(activity, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, account.id)
            .putExtra(AccountLoginActivity.EXTRA_AUTO_REAUTH, true);
        activity.startActivity(intent);
        return true;
    }

    public static boolean canAutoReauthenticate(@NonNull AccountVault.Account account, long now) {
        return account.type == AccountVault.Type.PASSWORD && account.password != null
            && !account.password.isEmpty() && (account.rejected || account.refreshToken == null
            || account.refreshExpiry <= now);
    }
}
