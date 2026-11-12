package com.maxwai.nclientv3;

import android.app.Application;
import android.os.Looper;

import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.widgets.ChipTag;
import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchOnlineTagsTest {
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
                chip.performClick();
                chip.performClick();
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
}
