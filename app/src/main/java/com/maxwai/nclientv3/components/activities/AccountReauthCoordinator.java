package com.maxwai.nclientv3.components.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.maxwai.nclientv3.AccountLoginActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.AccountRepository;
import com.maxwai.nclientv3.settings.AccountRuntime;
import com.maxwai.nclientv3.settings.AccountVault;

/** Starts one foreground reauthentication flow for an expired saved-password account. */
public final class AccountReauthCoordinator {
    private static final Object LOCK = new Object();
    private static String launchedAccountId;
    private static long launchedGeneration = Long.MIN_VALUE;

    private AccountReauthCoordinator() {
    }

    public static boolean maybeLaunch(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || activity instanceof AccountLoginActivity
            || !(activity instanceof LifecycleOwner)
            || !((LifecycleOwner) activity).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
            || !isEnabled(activity)) return false;
        AccountRepository repository = AccountRuntime.repository();
        if (repository == null || !repository.isOpen()) return false;
        AccountVault.State state = repository.snapshot();
        AccountVault.Account account = state == null ? null : state.activeAccount();
        long generation = repository.identityGeneration();
        if (account == null || generation < 0 || !canAutoReauthenticate(account, System.currentTimeMillis())) return false;
        synchronized (LOCK) {
            if (account.id.equals(launchedAccountId) && generation == launchedGeneration) return false;
            launchedAccountId = account.id;
            launchedGeneration = generation;
        }
        Intent intent = new Intent(activity, AccountLoginActivity.class)
            .putExtra(AccountLoginActivity.EXTRA_ACCOUNT_ID, account.id)
            .putExtra(AccountLoginActivity.EXTRA_AUTO_REAUTH_GENERATION, generation)
            .putExtra(AccountLoginActivity.EXTRA_AUTO_REAUTH, true);
        activity.startActivity(intent);
        return true;
    }

    public static boolean isEnabled(@NonNull Context context) {
        return context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
            .getBoolean(context.getString(R.string.preference_key_auto_reauthenticate), false);
    }

    public static boolean canAutoReauthenticate(@NonNull AccountVault.Account account, long now) {
        return account.type == AccountVault.Type.PASSWORD && account.password != null
            && !account.password.isEmpty() && (account.rejected || account.refreshToken == null
            || account.refreshExpiry <= now);
    }
}
