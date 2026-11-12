package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AuthStore {
    private static final String AUTH_PREFERENCES = "Auth";
    private static final String KEY_TYPE = "type";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_VALID = "valid";
    private static final java.util.concurrent.atomic.AtomicLong identityGeneration = new java.util.concurrent.atomic.AtomicLong();
    private static int identityWriters;
    private static final Object generationLock = new Object();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> identityObservers =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void observeIdentityChanges(@NonNull Runnable observer) {
        identityObservers.addIfAbsent(observer);
    }

    public static void removeIdentityObserver(@NonNull Runnable observer) {
        identityObservers.remove(observer);
    }

    public static void notifyAccountProfileChanged() {
        main.post(() -> {
            for (Runnable observer : identityObservers) observer.run();
        });
    }

    public static long identityGeneration() {
        synchronized (generationLock) {
            return identityWriters == 0 ? identityGeneration.get() : -1;
        }
    }

    static void beginIdentityChange() {
        synchronized (generationLock) {
            identityWriters++;
            identityGeneration.incrementAndGet();
        }
    }

    static void endIdentityChange() {
        boolean stable;
        synchronized (generationLock) {
            identityGeneration.incrementAndGet();
            identityWriters--;
            stable = identityWriters == 0;
        }
        if (stable) main.post(() -> {
            for (Runnable observer : identityObservers) observer.run();
        });
    }

    private AuthStore() {
    }

    @NonNull
    static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getSharedPreferences(AUTH_PREFERENCES, Context.MODE_PRIVATE);
    }

    public static void observeChanges(@NonNull Context context,
                                      @NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        getPreferences(context).registerOnSharedPreferenceChangeListener(listener);
    }

    public static void removeChangesObserver(@NonNull Context context,
                                             @NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        getPreferences(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static synchronized void saveApiKey(@NonNull Context context, @NonNull String apiKey, boolean valid) {
        String normalized = apiKey.trim();
        boolean changed = !normalized.equals(getApiKey(context)) || hasValidApiKey(context) != valid;
        if (changed) beginIdentityChange();
        try {
        getPreferences(context).edit()
            .putString(KEY_TYPE, AuthCredentials.Type.API_KEY.name())
            .putString(KEY_SECRET, normalized)
            .putBoolean(KEY_VALID, valid)
            .apply();
        } finally {
            if (changed) endIdentityChange();
        }
    }

    public static synchronized void clear(@NonNull Context context) {
        SharedPreferences preferences = getPreferences(context);
        boolean changed = preferences.contains(KEY_TYPE) || preferences.contains(KEY_SECRET) || preferences.contains(KEY_VALID);
        if (changed) beginIdentityChange();
        try {
        getPreferences(context).edit().remove(KEY_TYPE).remove(KEY_SECRET).remove(KEY_VALID).apply();
        } finally {
            if (changed) endIdentityChange();
        }
    }

    @Nullable
    public static AuthCredentials getCredentials(@NonNull Context context) {
        if (AccountRuntime.repository() != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            if (account == null || account.type != AccountVault.Type.API_KEY) return null;
            return new AuthCredentials(AuthCredentials.Type.API_KEY, account.apiKey);
        }
        SharedPreferences preferences = getPreferences(context);
        String typeName = preferences.getString(KEY_TYPE, null);
        String secret = preferences.getString(KEY_SECRET, null);
        if (typeName == null || secret == null || secret.trim().isEmpty()) return null;
        try {
            return new AuthCredentials(AuthCredentials.Type.valueOf(typeName), secret.trim());
        } catch (IllegalArgumentException ignore) {
            clear(context);
            return null;
        }
    }

    public static boolean hasCredentials(@NonNull Context context) {
        return getCredentials(context) != null;
    }

    public static boolean hasApiKey(@NonNull Context context) {
        AuthCredentials credentials = getCredentials(context);
        return credentials != null && credentials.getType() == AuthCredentials.Type.API_KEY;
    }

    public static boolean hasValidApiKey(@NonNull Context context) {
        if (AccountRuntime.repository() != null) {
            AccountVault.Account account = AccountRuntime.activeAccount();
            return account != null && account.type == AccountVault.Type.API_KEY && !account.rejected;
        }
        AuthCredentials credentials = getCredentials(context);
        if (credentials == null || credentials.getType() != AuthCredentials.Type.API_KEY) return false;

        SharedPreferences preferences = getPreferences(context);
        if (!preferences.contains(KEY_VALID)) return true;
        return preferences.getBoolean(KEY_VALID, false);
    }

    @Nullable
    public static String activeIdentity(@NonNull Context context) {
        if (identityGeneration() < 0) return null;
        String session = AuthSessionStore.identity(context);
        if (session != null) return session;
        if (AuthSessionStore.hasStoredSession(context)) return null;
        String key = hasValidApiKey(context) ? getApiKey(context) : null;
        return key == null ? null : ApiAuthInterceptor.fingerprint(key);
    }

    public static boolean hasOnlineIdentity(@NonNull Context context) {
        return activeIdentity(context) != null;
    }

    public static synchronized void setApiKeyValidation(@NonNull Context context, boolean valid) {
        if (!hasApiKey(context)) return;
        boolean changed = hasValidApiKey(context) != valid;
        if (changed) beginIdentityChange();
        try {
        getPreferences(context).edit().putBoolean(KEY_VALID, valid).apply();
        } finally {
            if (changed) endIdentityChange();
        }
    }

    public static synchronized void setApiKeyValidationIfCurrent(@NonNull Context context,
                                                                  @NonNull String apiKey, long generation, boolean valid) {
        AccountRepository repository = AccountRuntime.repository();
        if (repository != null) {
            if (valid) return;
            AccountVault.Account account = AccountRuntime.activeAccount();
            long accountGeneration = repository.identityGeneration();
            if (generation < 0 || generation != identityGeneration() || accountGeneration < 0 || account == null
                || account.type != AccountVault.Type.API_KEY || !apiKey.equals(account.apiKey)) return;
            AccountRuntime.execute(store -> store.reject(account.id, accountGeneration), error -> {
                if (error != null) com.maxwai.nclientv3.utility.LogUtility.w("Unable to update account status: "
                    + error.getClass().getSimpleName());
            });
            return;
        }
        if (generation >= 0 && generation == identityGeneration() && apiKey.equals(getApiKey(context)))
            setApiKeyValidation(context, valid);
    }

    @Nullable
    public static String getApiKey(@NonNull Context context) {
        AuthCredentials credentials = getCredentials(context);
        if (credentials == null || credentials.getType() != AuthCredentials.Type.API_KEY) return null;
        return credentials.getSecret();
    }

    @Nullable
    public static String getAuthorizationHeader(@NonNull Context context) {
        AuthCredentials credentials = getCredentials(context);
        return credentials == null ? null : credentials.toAuthorizationHeader();
    }
}
