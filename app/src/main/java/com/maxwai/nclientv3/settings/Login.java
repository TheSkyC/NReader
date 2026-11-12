package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.loginapi.User;
import com.maxwai.nclientv3.utility.Utility;

import java.util.List;
import java.util.Objects;

import okhttp3.HttpUrl;

public class Login {
    public static HttpUrl BASE_HTTP_URL;
    private static volatile User user;
    private static volatile String userIdentity;
    private static boolean accountTag;
    private static Context appContext;
    private static String onlineTagsIdentity;
    private static long onlineTagsRevision;
    private static long onlineTagsLoadSequence;
    private static long onlineTagsInstalledSequence;
    private static long onlineTagsMinimumSequence;
    private static String pendingOnlineTagIdentity;
    private static long pendingOnlineTagOperation;
    private static long onlineTagOperationSequence;
    private static long onlineTagsAuthGeneration = -1;
    private static int onlineTagsRestores;
    private static long onlineTagsRestoreGeneration;
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> onlineTagsObservers =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void observeOnlineTags(@NonNull Runnable observer) {
        onlineTagsObservers.addIfAbsent(observer);
    }

    public static void removeOnlineTagsObserver(@NonNull Runnable observer) {
        onlineTagsObservers.remove(observer);
    }

    private static void notifyOnlineTagsChanged() {
        main.post(() -> {
            for (Runnable observer : onlineTagsObservers) observer.run();
        });
    }

    public static void initLogin(@NonNull Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("Settings", 0);
        accountTag = preferences.getBoolean(context.getString(R.string.preference_key_use_account_tag), false);
        BASE_HTTP_URL = HttpUrl.get(Utility.getBaseUrl());
    }

    public static boolean useAccountTag() {
        return accountTag && hasCurrentOnlineTags();
    }

    public static boolean wantsAccountTag() {
        return accountTag && appContext != null
            && (AuthSessionStore.hasStoredSession(appContext) || AuthStore.hasApiKey(appContext));
    }

    public static synchronized boolean hasCurrentOnlineTags() {
        synchronizeOnlineTagsGeneration();
        return onlineTagsAuthGeneration >= 0 && onlineTagsRestores == 0 && pendingOnlineTagIdentity == null
            && appContext != null && onlineTagsIdentity != null
            && onlineTagsIdentity.equals(AuthStore.activeIdentity(appContext));
    }

    @Nullable
    public static synchronized String currentOnlineTagsIdentity() {
        return hasCurrentOnlineTags() ? onlineTagsIdentity : null;
    }

    public static synchronized long beginOnlineTagsLoad(@NonNull Context context, @NonNull String identity) {
        synchronizeOnlineTagsGeneration();
        releaseStaleMutation(context);
        if (onlineTagsAuthGeneration < 0 || onlineTagsRestores != 0 || pendingOnlineTagIdentity != null || !identity.equals(AuthStore.activeIdentity(context))) return -1;
        return ++onlineTagsLoadSequence;
    }

    public static synchronized long currentOnlineTagsRevision() {
        return hasCurrentOnlineTags() && pendingOnlineTagIdentity == null ? onlineTagsRevision : -1;
    }

    public static synchronized long onlineTagsInvalidationSequence() {
        synchronizeOnlineTagsGeneration();
        return onlineTagsMinimumSequence;
    }

    public static synchronized boolean isOnlineTagMutationPending() {
        synchronizeOnlineTagsGeneration();
        return onlineTagsRestores != 0 || pendingOnlineTagIdentity != null;
    }

    public static synchronized void beginOnlineTagsRestore() {
        onlineTagsRestores++;
        onlineTagsRestoreGeneration++;
        onlineTagsIdentity = null;
        onlineTagsRevision++;
        onlineTagsMinimumSequence = ++onlineTagsLoadSequence;
        notifyOnlineTagsChanged();
    }

    public static synchronized long onlineTagsRestoreGeneration() {
        return onlineTagsRestoreGeneration;
    }

    public static synchronized void endOnlineTagsRestore() {
        if (onlineTagsRestores <= 0) throw new IllegalStateException("Unbalanced online tag restore");
        onlineTagsRestores--;
        notifyOnlineTagsChanged();
    }

    public static synchronized boolean replaceOnlineTags(@NonNull Context context, @NonNull String identity,
                                                          long revision, @NonNull List<Tag> tags) {
        synchronizeOnlineTagsGeneration();
        if (onlineTagsAuthGeneration < 0 || onlineTagsRestores != 0 || revision < onlineTagsMinimumSequence || revision <= onlineTagsInstalledSequence
            || pendingOnlineTagIdentity != null
            || !identity.equals(AuthStore.activeIdentity(context))) return false;
        onlineTagsIdentity = null;
        Queries.TagTable.replaceOnlineTags(tags);
        if (onlineTagsAuthGeneration != AuthStore.identityGeneration()
            || !identity.equals(AuthStore.activeIdentity(context))) {
            synchronizeOnlineTagsGeneration();
            return false;
        }
        onlineTagsIdentity = identity;
        onlineTagsInstalledSequence = revision;
        onlineTagsRevision++;
        notifyOnlineTagsChanged();
        return true;
    }

    public static synchronized long beginOnlineTagMutation(@NonNull Context context, @NonNull String identity) {
        synchronizeOnlineTagsGeneration();
        releaseStaleMutation(context);
        if (onlineTagsAuthGeneration < 0 || pendingOnlineTagIdentity != null || !identity.equals(AuthStore.activeIdentity(context))
            || !identity.equals(onlineTagsIdentity)) return -1;
        pendingOnlineTagIdentity = identity;
        pendingOnlineTagOperation = ++onlineTagOperationSequence;
        onlineTagsMinimumSequence = ++onlineTagsLoadSequence;
        notifyOnlineTagsChanged();
        return pendingOnlineTagOperation;
    }

    private static void releaseStaleMutation(@NonNull Context context) {
        if (pendingOnlineTagIdentity != null
            && !pendingOnlineTagIdentity.equals(AuthStore.activeIdentity(context))) pendingOnlineTagIdentity = null;
    }

    private static void synchronizeOnlineTagsGeneration() {
        long generation = AuthStore.identityGeneration();
        if (generation == onlineTagsAuthGeneration) return;
        onlineTagsAuthGeneration = generation;
        onlineTagsIdentity = null;
        pendingOnlineTagIdentity = null;
        onlineTagsRevision++;
        onlineTagsMinimumSequence = ++onlineTagsLoadSequence;
        notifyOnlineTagsChanged();
    }

    public static synchronized boolean isOnlineTagMutationCurrent(@NonNull Context context,
                                                                  @NonNull String identity, long operation) {
        synchronizeOnlineTagsGeneration();
        return operation == pendingOnlineTagOperation && identity.equals(pendingOnlineTagIdentity)
            && identity.equals(AuthStore.activeIdentity(context));
    }

    public static synchronized boolean finishOnlineTagMutation(@NonNull Context context, @NonNull String identity,
                                                                long operation, @NonNull Tag tag, boolean add) {
        synchronizeOnlineTagsGeneration();
        if (operation != pendingOnlineTagOperation || !identity.equals(pendingOnlineTagIdentity)) return false;
        if (!identity.equals(AuthStore.activeIdentity(context)) || !identity.equals(onlineTagsIdentity)) {
            pendingOnlineTagIdentity = null;
            notifyOnlineTagsChanged();
            return false;
        }
        if (add) Queries.TagTable.insert(tag);
        Queries.TagTable.updateBlacklistedTag(tag, add);
        pendingOnlineTagIdentity = null;
        onlineTagsRevision++;
        notifyOnlineTagsChanged();
        return true;
    }

    public static synchronized boolean invalidateOnlineTags(@NonNull String identity, long operation) {
        synchronizeOnlineTagsGeneration();
        boolean pending = operation == pendingOnlineTagOperation && Objects.equals(identity, pendingOnlineTagIdentity);
        if (!pending) return false;
        boolean current = Objects.equals(identity, onlineTagsIdentity);
        pendingOnlineTagIdentity = null;
        if (current) onlineTagsIdentity = null;
        onlineTagsRevision++;
        onlineTagsMinimumSequence = ++onlineTagsLoadSequence;
        notifyOnlineTagsChanged();
        return true;
    }

    public static boolean isLogged(@Nullable Context context) {
        Context authContext = context != null ? context.getApplicationContext() : appContext;
        if (authContext != null && AuthStore.activeIdentity(authContext) != null) {
            if (context != null && getUser() == null) {
                User.createUser(context, null);
                new LoadTags(context).start();
            }
            return true;
        }
        return false;
    }

    public static boolean isLogged() {
        return isLogged(null);
    }


    public static User getUser() {
        if (appContext == null || !Objects.equals(userIdentity, AuthStore.activeIdentity(appContext))) return null;
        return user;
    }

    public static synchronized void updateUser(User user) {
        synchronizeOnlineTagsGeneration();
        Login.user = user;
        userIdentity = user == null || appContext == null ? null : AuthStore.activeIdentity(appContext);
        if (user == null) {
            onlineTagsIdentity = null;
            pendingOnlineTagIdentity = null;
            onlineTagsRevision++;
            onlineTagsMinimumSequence = ++onlineTagsLoadSequence;
            notifyOnlineTagsChanged();
        }
    }

    public static synchronized boolean updateUserIfCurrent(@NonNull Context context, @NonNull String identity,
                                                            long generation, @NonNull User value) {
        if (generation < 0 || generation != AuthStore.identityGeneration() || !identity.equals(AuthStore.activeIdentity(context)))
            return false;
        user = value;
        userIdentity = identity;
        return true;
    }


    public static boolean isOnlineTags(Tag tag) {
        String identity = currentOnlineTagsIdentity();
        return identity != null && Queries.TagTable.isBlackListed(tag)
            && identity.equals(currentOnlineTagsIdentity());
    }

}
