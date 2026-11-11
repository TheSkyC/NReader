// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class OnlineFavoriteClient {
    private static final int MAX_CACHED_STATES = 512;
    private static final Map<String, Boolean> confirmedStates = new LinkedHashMap<>(128, 0.75f, true);
    private static final Set<String> inFlight = new HashSet<>();
    private static long mutationVersion;
    private static long identityVersion;
    private static long discardedVersion;
    private static final Map<String, Long> changedVersions = new LinkedHashMap<>();
    private static final CopyOnWriteArrayList<WeakReference<IntConsumer>> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String activeIdentity;
    private static SharedPreferences.OnSharedPreferenceChangeListener authObserver;
    private static Context authContext;

    public interface Result {
        void onSuccess(boolean favorited);
        void onFailure();
        default void onUncertain() { onFailure(); }
    }

    private OnlineFavoriteClient() {
    }

    public static void observe(@NonNull Context context, @NonNull IntConsumer listener) {
        observeAuthChanges(context);
        listeners.add(new WeakReference<>(listener));
    }

    private static void observeAuthChanges(Context context) {
        synchronized (confirmedStates) {
            Context application = context.getApplicationContext();
            if (authContext != application) {
                if (authContext != null) AuthStore.removeChangesObserver(authContext, authObserver);
                authContext = application;
                authObserver = (preferences, key) -> identity(application);
                AuthStore.observeChanges(application, authObserver);
            }
        }
    }

    public static void removeObserver(@NonNull IntConsumer listener) {
        listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
    }

    private static void notifyStateChanged(int galleryId) {
        main.post(() -> {
            for (WeakReference<IntConsumer> reference : listeners) {
                IntConsumer listener = reference.get();
                if (listener == null) listeners.remove(reference);
                else listener.accept(galleryId);
            }
        });
    }

    @Nullable
    public static Boolean parseResponse(@Nullable String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject response = new JSONObject(body);
            if (!response.has("favorited")) return null;
            Object value = response.get("favorited");
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    public static void toggle(@NonNull Context context, int galleryId, @NonNull Result result) {
        toggle(context, galleryId, Global.getClient(context), result);
    }

    static void toggle(@NonNull Context context, int galleryId, @NonNull OkHttpClient client,
                       @NonNull Result result) {
        String identity = identity(context);
        if (identity == null || galleryId <= 0) {
            result.onFailure();
            return;
        }
        String key = cacheKey(identity, galleryId);
        Boolean currentFavorite;
        boolean started;
        synchronized (confirmedStates) {
            started = inFlight.add(key);
            if (started) markChanged(key);
            currentFavorite = confirmedStates.get(key);
        }
        if (!started) {
            result.onFailure();
            return;
        }
        Result finished = new Result() {
            private void release() {
                synchronized (confirmedStates) {
                    if (inFlight.remove(key)) markChanged(key);
                }
            }
            @Override public void onSuccess(boolean favorited) {
                release();
                result.onSuccess(favorited);
            }
            @Override public void onFailure() {
                release();
                result.onFailure();
            }
        };
        try {
            if (currentFavorite != null) {
                mutate(context, galleryId, currentFavorite, identity, client, finished);
                return;
            }
            Request request = request(galleryId, "GET", identity);
            enqueue(context, client, request, identity, new Result() {
            @Override public void onSuccess(boolean favorited) {
                if (!identity.equals(identity(context))) {
                    finished.onFailure();
                    return;
                }
                remember(identity, galleryId, favorited);
                mutate(context, galleryId, favorited, identity, client, finished);
            }
            @Override public void onFailure() { finished.onFailure(); }
            });
        } catch (RuntimeException exception) {
            LogUtility.w("Unable to update online favorite", exception);
            finished.onFailure();
        }
    }

    @Nullable
    public static Boolean cachedState(@NonNull Context context, int galleryId) {
        String identity = identity(context);
        if (identity == null || galleryId <= 0) return null;
        synchronized (confirmedStates) {
            return confirmedStates.get(cacheKey(identity, galleryId));
        }
    }

    public static void rememberState(@NonNull Context context, int galleryId, boolean favorited) {
        String identity = identity(context);
        if (identity != null && galleryId > 0) remember(identity, galleryId, favorited);
    }

    @Nullable
    public static String sessionIdentity(@NonNull Context context) {
        return identity(context);
    }

    static final class FavoriteListSnapshot {
        final String identity;
        final long version;
        final long identityVersion;
        final Set<String> inFlightAtStart;

        FavoriteListSnapshot(String identity, long version, long identityVersion, Set<String> inFlightAtStart) {
            this.identity = identity;
            this.version = version;
            this.identityVersion = identityVersion;
            this.inFlightAtStart = inFlightAtStart;
        }
    }

    @Nullable
    static FavoriteListSnapshot beginFavoriteList(Context context) {
        observeAuthChanges(context);
        String identity = identity(context);
        if (identity == null) return null;
        synchronized (confirmedStates) {
            return identity.equals(activeIdentity)
                ? new FavoriteListSnapshot(identity, mutationVersion, identityVersion, new HashSet<>(inFlight)) : null;
        }
    }

    static void acceptFavoriteList(Context context, FavoriteListSnapshot snapshot, Collection<Integer> ids) {
        if (snapshot == null || !snapshot.identity.equals(identity(context))) return;
        boolean changed = false;
        synchronized (confirmedStates) {
            if (!snapshot.identity.equals(activeIdentity) || snapshot.identityVersion != identityVersion
                || snapshot.version < discardedVersion) return;
            for (int id : ids) {
                if (id <= 0) continue;
                String key = cacheKey(snapshot.identity, id);
                Long changedAt = changedVersions.get(key);
                if (snapshot.inFlightAtStart.contains(key) || inFlight.contains(key)
                    || (changedAt != null && changedAt > snapshot.version)) continue;
                Boolean previous = confirmedStates.put(key, true);
                changed |= !Boolean.TRUE.equals(previous);
                while (confirmedStates.size() > MAX_CACHED_STATES) {
                    confirmedStates.remove(confirmedStates.keySet().iterator().next());
                    changed = true;
                }
            }
        }
        if (changed) notifyStateChanged(0);
    }

    private static void mutate(Context context, int galleryId, boolean remove,
                               String identity, OkHttpClient client, Result result) {
        try {
            enqueue(context, client, request(galleryId, remove ? "DELETE" : "POST", identity), identity, new Result() {
            @Override public void onSuccess(boolean favorited) {
                remember(identity, galleryId, favorited);
                result.onSuccess(favorited);
            }
            @Override public void onFailure() { result.onFailure(); }
            @Override public void onUncertain() {
                boolean removed;
                synchronized (confirmedStates) {
                    removed = confirmedStates.remove(cacheKey(identity, galleryId)) != null;
                }
                if (removed) notifyStateChanged(galleryId);
                result.onFailure();
            }
            });
        } catch (RuntimeException exception) {
            LogUtility.w("Unable to update online favorite", exception);
            result.onFailure();
        }
    }

    private static Request request(int galleryId, String method, String identity) {
        String url = String.format(Locale.US, Utility.getApiBaseUrl() + "galleries/%d/favorite", galleryId);
        return new Request.Builder().url(url).tag(ApiAuthInterceptor.ExpectedIdentity.class,
                new ApiAuthInterceptor.ExpectedIdentity(identity))
            .method(method, "GET".equals(method) ? null : RequestBody.EMPTY).build();
    }

    private static void enqueue(Context context, OkHttpClient client, Request request, String identity, Result result) {
        try {
            client.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                        result.onUncertain();
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (Response received = response) {
                            if (!received.isSuccessful()) {
                                if (received.code() >= 500) result.onUncertain();
                                else result.onFailure();
                                return;
                            }
                            if (received.body() == null) {
                                result.onUncertain();
                                return;
                            }
                            Boolean favorited = parseResponse(received.body().string());
                            if (favorited == null || !identity.equals(identity(context))) result.onUncertain();
                            else result.onSuccess(favorited);
                        } catch (IOException exception) {
                            result.onUncertain();
                        }
                    }
                });
        } catch (RuntimeException exception) {
            LogUtility.w("Unable to update online favorite", exception);
            result.onFailure();
        }
    }

    @Nullable
    private static String identity(Context context) {
        if (!com.maxwai.nclientv3.settings.AuthStore.hasValidApiKey(context)) {
            boolean changed;
            synchronized (confirmedStates) {
                changed = activeIdentity != null;
                activeIdentity = null;
                if (changed) resetIdentityChanges();
                confirmedStates.clear();
            }
            if (changed) notifyStateChanged(0);
            return null;
        }
        String apiKey = com.maxwai.nclientv3.settings.AuthStore.getApiKey(context);
        if (apiKey == null) return null;
        try {
            String identity = ApiAuthInterceptor.fingerprint(apiKey);
            boolean changed;
            synchronized (confirmedStates) {
                changed = !identity.equals(activeIdentity);
                if (changed) {
                    confirmedStates.clear();
                    activeIdentity = identity;
                    resetIdentityChanges();
                }
            }
            if (changed) notifyStateChanged(0);
            return identity;
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private static void remember(String identity, int galleryId, boolean favorited) {
        boolean changed;
        boolean evicted = false;
        synchronized (confirmedStates) {
            if (!identity.equals(activeIdentity)) return;
            Boolean previous = confirmedStates.put(cacheKey(identity, galleryId), favorited);
            changed = previous == null || previous != favorited;
            if (changed) markChanged(cacheKey(identity, galleryId));
            while (confirmedStates.size() > MAX_CACHED_STATES) {
                String oldest = confirmedStates.keySet().iterator().next();
                confirmedStates.remove(oldest);
                evicted = true;
            }
        }
        if (evicted) notifyStateChanged(0);
        else if (changed) notifyStateChanged(galleryId);
    }

    private static String cacheKey(String identity, int galleryId) {
        return identity + ':' + galleryId;
    }

    private static void markChanged(String key) {
        changedVersions.remove(key);
        changedVersions.put(key, ++mutationVersion);
        if (changedVersions.size() > MAX_CACHED_STATES) {
            String oldest = changedVersions.keySet().iterator().next();
            discardedVersion = changedVersions.remove(oldest);
        }
    }

    private static void resetIdentityChanges() {
        identityVersion++;
        changedVersions.clear();
        discardedVersion = mutationVersion;
    }
}
