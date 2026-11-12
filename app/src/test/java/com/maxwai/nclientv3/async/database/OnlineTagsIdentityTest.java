package com.maxwai.nclientv3.async.database;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.R;

import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineTagsIdentityTest {
    @Test public void blacklistChangesNotifyObserversAndRemovalStopsNotifications() {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthStore.saveApiKey(context, "observer-fixture-key", true);
        Login.updateUser(null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        Runnable observer = notifications::incrementAndGet;
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            Login.observeOnlineTags(observer);
            String identity = AuthStore.activeIdentity(context);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, identity, Login.beginOnlineTagsLoad(context, identity),
                Collections.singletonList(tag)));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, notifications.get());
            long operation = Login.beginOnlineTagMutation(context, identity);
            assertFalse(Login.hasCurrentOnlineTags());
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, notifications.get());
            assertTrue(Login.finishOnlineTagMutation(context, identity, operation, tag, false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3, notifications.get());
            long failedOperation = Login.beginOnlineTagMutation(context, identity);
            assertTrue(Login.invalidateOnlineTags(identity, failedOperation));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(5, notifications.get());
            assertFalse(Login.hasCurrentOnlineTags());
            Login.removeOnlineTagsObserver(observer);
            Login.updateUser(null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(5, notifications.get());
        } finally {
            Login.removeOnlineTagsObserver(observer);
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
        }
    }

    @Test public void storedAccountSessionDoesNotReadLegacyApiKeyTags() {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthSessionStore.clear(context);
        AuthStore.saveApiKey(context, "test-key", true);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            database.execSQL("INSERT INTO Tags VALUES (42, 'example', 3, 1, 0, 1)");
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            String identity = AuthStore.activeIdentity(context);
            long revision = Login.beginOnlineTagsLoad(context, identity);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, identity, revision, Collections.singletonList(tag)));
            assertEquals(1, Queries.TagTable.getAllOnlineBlacklisted().size());
            assertTrue(AuthSessionStore.replace(context,
                AuthSessionStore.tokenCookies("test-access", "test-refresh"), null));
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            String session = AuthStore.activeIdentity(context);
            assertTrue(Login.replaceOnlineTags(context, session, Login.beginOnlineTagsLoad(context, session),
                Collections.singletonList(tag)));
            assertEquals(1, Queries.TagTable.getAllOnlineBlacklisted().size());
            context.getSharedPreferences("Auth", 0).edit().putBoolean("session_rejected", true).commit();
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
            assertFalse(Login.hasCurrentOnlineTags());
            AuthSessionStore.clear(context);
            AuthStore.clear(context);
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
        } finally {
            Queries.setDb(null);
            AuthSessionStore.clear(context);
            AuthStore.clear(context);
        }
    }

    @Test public void staleLoadsAndChangedApiKeysCannotExposeOldTags() {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthSessionStore.clear(context);
        AuthStore.saveApiKey(context, "first-key", true);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            String first = AuthStore.activeIdentity(context);
            long older = Login.beginOnlineTagsLoad(context, first);
            long newer = Login.beginOnlineTagsLoad(context, first);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, first, older, Collections.singletonList(tag)));
            assertTrue(Login.replaceOnlineTags(context, first, newer, Collections.singletonList(tag)));
            assertFalse(Login.replaceOnlineTags(context, first, older, Collections.singletonList(tag)));
            long firstOperation = Login.beginOnlineTagMutation(context, first);
            assertTrue(firstOperation > 0);
            assertEquals(-1, Login.beginOnlineTagMutation(context, first));
            assertFalse(Login.replaceOnlineTags(context, first, newer, Collections.singletonList(tag)));
            AuthStore.saveApiKey(context, "second-key", true);
            Login.updateUser(null);
            String second = AuthStore.activeIdentity(context);
            long secondLoad = Login.beginOnlineTagsLoad(context, second);
            assertTrue(Login.replaceOnlineTags(context, second, secondLoad, Collections.singletonList(tag)));
            long secondOperation = Login.beginOnlineTagMutation(context, second);
            assertTrue(secondOperation > 0);
            assertFalse(Login.finishOnlineTagMutation(context, first, firstOperation, tag, false));
            assertFalse(Login.invalidateOnlineTags(first, firstOperation));
            assertTrue(Login.finishOnlineTagMutation(context, second, secondOperation, tag, false));
            assertFalse(Login.replaceOnlineTags(context, first, newer, Collections.singletonList(tag)));
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
        } finally {
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
        }
    }

    @Test public void returningToPreviousKeyDoesNotRestoreOldLoadOrMutation() {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthStore.saveApiKey(context, "first-fixture-key", true);
        Login.updateUser(null);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            String first = AuthStore.activeIdentity(context);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, first, Login.beginOnlineTagsLoad(context, first),
                Collections.singletonList(tag)));
            long staleLoad = Login.beginOnlineTagsLoad(context, first);
            long staleMutation = Login.beginOnlineTagMutation(context, first);
            AuthStore.saveApiKey(context, "second-fixture-key", true);
            Login.updateUser(null);
            AuthStore.saveApiKey(context, "first-fixture-key", true);
            Login.updateUser(null);
            assertFalse(Login.hasCurrentOnlineTags());
            assertFalse(Login.replaceOnlineTags(context, first, staleLoad, Collections.singletonList(tag)));
            assertFalse(Login.finishOnlineTagMutation(context, first, staleMutation, tag, false));
            assertTrue(Queries.TagTable.getAllOnlineBlacklisted().isEmpty());
        } finally {
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
        }
    }

    @Test public void backgroundIdentityRoundTripInvalidatesOldBlacklistOperations() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthStore.saveApiKey(context, "first-background-key", true);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            String identity = AuthStore.activeIdentity(context);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, identity, Login.beginOnlineTagsLoad(context, identity),
                Collections.singletonList(tag)));
            long oldLoad = Login.beginOnlineTagsLoad(context, identity);
            long oldMutation = Login.beginOnlineTagMutation(context, identity);
            Thread switcher = new Thread(() -> {
                AuthStore.saveApiKey(context, "second-background-key", true);
                AuthStore.saveApiKey(context, "first-background-key", true);
            });
            switcher.start();
            switcher.join(5000);
            assertFalse(switcher.isAlive());
            assertFalse(Login.hasCurrentOnlineTags());
            assertFalse(Login.isOnlineTagMutationCurrent(context, identity, oldMutation));
            assertFalse(Login.finishOnlineTagMutation(context, identity, oldMutation, tag, false));
            assertFalse(Login.replaceOnlineTags(context, identity, oldLoad, Collections.singletonList(tag)));
        } finally {
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
        }
    }

    @Test public void defaultAndAdvancedSearchRespectOnlineTagSelection() {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        Global.initFromShared(context);
        AuthStore.saveApiKey(context, "search-key", true);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            String identity = AuthStore.activeIdentity(context);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, identity, Login.beginOnlineTagsLoad(context, identity),
                Collections.singletonList(tag)));
            context.getSharedPreferences("Settings", 0).edit()
                .putBoolean(context.getString(R.string.preference_key_use_account_tag), false).commit();
            Login.initLogin(context);
            assertFalse(InspectorV3.basicInspector(context, 1, null).getUrl().contains("example"));
            context.getSharedPreferences("Settings", 0).edit()
                .putBoolean(context.getString(R.string.preference_key_use_account_tag), true).commit();
            Login.initLogin(context);
            assertTrue(InspectorV3.basicInspector(context, 1, null).getUrl().contains("example"));
            tag.setStatus(TagStatus.AVOIDED);
            assertTrue(InspectorV3.searchInspector(context, "test", Collections.singletonList(tag),
                1, Global.getSortType(), null, null).getUrl().contains("example"));
        } finally {
            context.getSharedPreferences("Settings", 0).edit()
                .remove(context.getString(R.string.preference_key_use_account_tag)).commit();
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
        }
    }

    @Test public void languageAndCategoryBlacklistEntriesReachSearch() {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        context.getSharedPreferences("Settings", 0).edit()
            .putBoolean(context.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(context);
        Login.initLogin(context);
        AuthStore.saveApiKey(context, "typed-fixture-key", true);
        Login.updateUser(null);
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            Queries.setDb(database);
            database.execSQL(Queries.TagTable.CREATE_TABLE);
            String identity = AuthStore.activeIdentity(context);
            Tag language = new Tag("fixture-language", 1, 90001, TagType.LANGUAGE, TagStatus.DEFAULT);
            Tag category = new Tag("fixture-category", 1, 90002, TagType.CATEGORY, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(context, identity, Login.beginOnlineTagsLoad(context, identity),
                java.util.Arrays.asList(language, category)));
            assertEquals(2, Queries.TagTable.getAllOnlineBlacklisted().size());
            String url = InspectorV3.basicInspector(context, 1, null).getUrl();
            assertTrue(url.contains("fixture-language"));
            assertTrue(url.contains("fixture-category"));
        } finally {
            Queries.setDb(null);
            AuthStore.clear(context);
            Login.updateUser(null);
            context.getSharedPreferences("Settings", 0).edit()
                .remove(context.getString(R.string.preference_key_use_account_tag)).commit();
            Login.initLogin(context);
        }
    }

    @Test public void enabledAccountFilterRemainsRequestedWhenCredentialsAreRejected() {
        Application context = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(context);
        AuthStore.clear(context);
        context.getSharedPreferences("Settings", 0).edit()
            .putBoolean(context.getString(R.string.preference_key_use_account_tag), true).commit();
        Login.initLogin(context);
        try {
            assertFalse(Login.wantsAccountTag());
            AuthStore.saveApiKey(context, "rejected-test-key", false);
            assertTrue(Login.wantsAccountTag());
            assertFalse(Login.useAccountTag());
            AuthStore.clear(context);
            assertTrue(AuthSessionStore.replace(context,
                AuthSessionStore.tokenCookies("test-access", "test-refresh"), null));
            context.getSharedPreferences("Auth", 0).edit().putBoolean("session_rejected", true).commit();
            assertTrue(Login.wantsAccountTag());
            assertFalse(Login.useAccountTag());
        } finally {
            AuthSessionStore.clear(context);
            AuthStore.clear(context);
            Login.updateUser(null);
            context.getSharedPreferences("Settings", 0).edit()
                .remove(context.getString(R.string.preference_key_use_account_tag)).commit();
            Login.initLogin(context);
        }
    }
}
