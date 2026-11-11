package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.loginapi.User;
import com.maxwai.nclientv3.utility.Utility;

import okhttp3.HttpUrl;

public class Login {
    public static HttpUrl BASE_HTTP_URL;
    private static volatile User user;
    private static volatile String userIdentity;
    private static boolean accountTag;
    private static Context appContext;

    public static void initLogin(@NonNull Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("Settings", 0);
        accountTag = preferences.getBoolean(context.getString(R.string.preference_key_use_account_tag), false);
        BASE_HTTP_URL = HttpUrl.get(Utility.getBaseUrl());
    }

    public static boolean useAccountTag() {
        return accountTag && (appContext == null || !AuthSessionStore.hasStoredSession(appContext));
    }

    public static void clearOnlineTags() {
        Queries.TagTable.removeAllBlacklisted();
    }

    public static void addOnlineTag(Tag tag) {
        Queries.TagTable.insert(tag);
        Queries.TagTable.updateBlacklistedTag(tag, true);
    }

    public static void removeOnlineTag(Tag tag) {
        Queries.TagTable.updateBlacklistedTag(tag, false);
    }

    public static boolean isLogged(@Nullable Context context) {
        Context authContext = context != null ? context.getApplicationContext() : appContext;
        if (authContext != null && !AuthSessionStore.hasStoredSession(authContext)
            && AuthStore.hasCredentials(authContext)) {
            if (context != null && getUser() == null) {
                User.createUser(context, user -> {
                    if (user != null) {
                        new LoadTags(context).start();
                    }
                });
            }
            return true;
        }
        return false;
    }

    public static boolean isLogged() {
        return isLogged(null);
    }


    public static User getUser() {
        if (appContext == null || AuthSessionStore.hasStoredSession(appContext)
            || !java.util.Objects.equals(userIdentity, AuthStore.activeIdentity(appContext))) return null;
        return user;
    }

    public static void updateUser(User user) {
        Login.user = user;
        userIdentity = user == null || appContext == null ? null : AuthStore.activeIdentity(appContext);
    }

    public static synchronized boolean updateUserIfCurrent(@NonNull Context context, @NonNull String identity,
                                                            @NonNull User value) {
        if (AuthSessionStore.hasStoredSession(context) || !identity.equals(AuthStore.activeIdentity(context)))
            return false;
        user = value;
        userIdentity = identity;
        return true;
    }


    public static boolean isOnlineTags(Tag tag) {
        return Queries.TagTable.isBlackListed(tag);
    }

}
