package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import androidx.appcompat.widget.SearchView;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.settings.AuthSessionStore;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.AccountRuntimeTestScope;

import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchOnlineTagsTest {
    @Rule public final AccountRuntimeTestScope accountRuntimeScope = new AccountRuntimeTestScope();
    @Test public void searchAppliesBlacklistChangedWhileScreenIsOpen() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            app.getSharedPreferences("Settings", 0).edit()
                .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
            Global.initFromShared(app);
            AuthStore.saveApiKey(app, "search-update-fixture", true);
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.emptyList()));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                Tag tag = new Tag("updated-blacklist", 100000, 4124, TagType.TAG, TagStatus.DEFAULT);
                Queries.TagTable.insert(tag);
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    Collections.singletonList(tag)));
                ChipTag found = null;
                for (int attempt = 0; attempt < 100 && found == null; attempt++) {
                    Thread.sleep(20);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    android.view.ViewGroup group = controller.get().findViewById(R.id.tag_group);
                    for (int index = 0; index < group.getChildCount(); index++) {
                        android.view.View view = group.getChildAt(index);
                        if (view instanceof ChipTag && "updated-blacklist".contentEquals(((ChipTag) view).getText()))
                            found = (ChipTag) view;
                    }
                }
                assertNotNull(found);
                assertEquals(TagStatus.AVOIDED, found.getTag().getStatus());
            }
        } finally {
            AuthStore.clear(app);
            Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
        }
    }

    @Test public void searchWaitsUntilCurrentIdentityAndRevisionAreApplied() {
        assertFalse(SearchActivity.onlineTagsReady(null, -1, "account-a", 1));
        assertFalse(SearchActivity.onlineTagsReady("account-a", 1, "account-a", 2));
        assertFalse(SearchActivity.onlineTagsReady("account-a", 1, "account-b", 1));
        assertFalse(SearchActivity.onlineTagsReady("account-a", 1, "account-a", -1));
        assertTrue(SearchActivity.onlineTagsReady("account-a", 1, "account-a", 1));
    }

    @Test public void popularBlacklistedTagIsAppliedAndClearedOnAccountChange() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            app.getSharedPreferences("Settings", 0).edit()
                .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
            Global.initFromShared(app);
            AuthStore.saveApiKey(app, "search-fixture-key", true);
            Tag tag = new Tag("fixture-blacklist", 100000, 4123, TagType.TAG, TagStatus.DEFAULT);
            Queries.TagTable.insert(tag);
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.singletonList(tag)));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                ChipTag chip = null;
                for (int attempt = 0; attempt < 100 && chip == null; attempt++) {
                    Thread.sleep(20);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    android.view.ViewGroup group = activity.findViewById(R.id.tag_group);
                    for (int index = 0; index < group.getChildCount(); index++) {
                        android.view.View view = group.getChildAt(index);
                        if (view instanceof ChipTag && "fixture-blacklist".contentEquals(((ChipTag) view).getText()))
                            chip = (ChipTag) view;
                    }
                }
                assertNotNull(chip);
                assertEquals(TagStatus.AVOIDED, chip.getTag().getStatus());
                SearchUiTestSupport.selectStatus(chip, 1);
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                AuthStore.saveApiKey(app, "different-fixture-key", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
            }
        } finally {
            AuthStore.clear(app);
            Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
        }
    }

    @Test public void blacklistDoesNotChangeATagWithTheSameNameAndDifferentId() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.deleteDatabase("Entries.db");
        String accountTagsKey = app.getString(R.string.preference_key_use_account_tag);
        String limitKey = app.getString(R.string.preference_key_favorite_limit);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            app.getSharedPreferences("Settings", 0).edit()
                .putBoolean(accountTagsKey, true).putInt(limitKey, 1).commit();
            Global.initFromShared(app);
            AuthStore.saveApiKey(app, "search-fixture-key", true);
            Tag popular = new Tag("same name", 100000, 4123, TagType.TAG, TagStatus.DEFAULT);
            Tag blocked = new Tag("same name", 1, 4124, TagType.TAG, TagStatus.DEFAULT);
            Queries.TagTable.insert(popular);
            Queries.TagTable.insert(blocked);
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.singletonList(blocked)));

            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                android.view.ViewGroup group = controller.get().findViewById(R.id.tag_group);
                ChipTag blockedChip = null;
                for (int attempt = 0; attempt < 100 && blockedChip == null; attempt++) {
                    Thread.sleep(20);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    blockedChip = findChipById(group, blocked.getId());
                }
                assertNotNull(blockedChip);
                assertEquals(TagStatus.AVOIDED, blockedChip.getTag().getStatus());
                ChipTag popularChip = findChipById(group, popular.getId());
                assertNotNull(popularChip);
                assertEquals(TagStatus.DEFAULT, popularChip.getTag().getStatus());
                AuthStore.saveApiKey(app, "different-fixture-key", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(blockedChip.getParent() == group);
                assertEquals(TagStatus.DEFAULT, popularChip.getTag().getStatus());
            }
        } finally {
            AuthStore.clear(app);
            Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit().remove(accountTagsKey).remove(limitKey).commit();
        }
    }

    @Test public void disablingAccountTagsBeforeSubmitKeepsOnlyManualSelection() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.deleteDatabase("Entries.db");
        String accountTagsKey = app.getString(R.string.preference_key_use_account_tag);
        String limitKey = app.getString(R.string.preference_key_favorite_limit);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            app.getSharedPreferences("Settings", 0).edit()
                .putBoolean(accountTagsKey, true).putInt(limitKey, 1).commit();
            Global.initFromShared(app);
            AuthStore.saveApiKey(app, "search-fixture-key", true);
            Tag manual = new Tag("manual", 100000, 4123, TagType.TAG, TagStatus.DEFAULT);
            Tag blocked = new Tag("blocked", 1, 4124, TagType.TAG, TagStatus.DEFAULT);
            Queries.TagTable.insert(manual);
            Queries.TagTable.insert(blocked);
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.singletonList(blocked)));

            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                android.view.ViewGroup group = activity.findViewById(R.id.tag_group);
                ChipTag blockedChip = null;
                for (int attempt = 0; attempt < 100 && blockedChip == null; attempt++) {
                    Thread.sleep(20);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    blockedChip = findChipById(group, blocked.getId());
                }
                assertNotNull(blockedChip);
                ChipTag manualChip = findChipById(group, manual.getId());
                assertNotNull(manualChip);
                SearchUiTestSupport.selectStatus(manualChip, 1);
                app.getSharedPreferences("Settings", 0).edit().putBoolean(accountTagsKey, false).commit();
                Global.initFromShared(app);
                assertFalse(Login.useAccountTag());
                assertTrue(Login.hasCurrentOnlineTags());

                ((SearchView) activity.findViewById(R.id.search)).setQuery("fixture", true);
                Intent search = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(search);
                ArrayList<Tag> selection = search.getParcelableArrayListExtra(activity.getPackageName() + ".TAGS");
                assertNotNull(selection);
                assertEquals(1, selection.size());
                assertEquals(manual.getId(), selection.get(0).getId());
                assertEquals(TagStatus.ACCEPTED, selection.get(0).getStatus());
            }
        } finally {
            AuthStore.clear(app);
            Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit().remove(accountTagsKey).remove(limitKey).commit();
        }
    }

    private static ChipTag findChipById(android.view.ViewGroup group, int id) {
        for (int index = 0; index < group.getChildCount(); index++) {
            android.view.View child = group.getChildAt(index);
            if (child instanceof ChipTag && ((ChipTag) child).getTag().getId() == id) return (ChipTag) child;
        }
        return null;
    }
}
