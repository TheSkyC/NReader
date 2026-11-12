package com.maxwai.nclientv3.components.widgets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import com.maxwai.nclientv3.TagFilterActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import androidx.recyclerview.widget.RecyclerView;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTypePageTest {
    public static class ControlledPage extends TagTypePage {
        final java.util.List<com.maxwai.nclientv3.loginapi.LoadTags.Completion> completions = new java.util.ArrayList<>();
        @Override void startOnlineTagsLoad(com.maxwai.nclientv3.loginapi.LoadTags.Completion completion) {
            completions.add(completion);
        }
    }

    @Test public void oldRefreshCannotOverwriteNewIdentityStatus() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "first-fixture", true);
        Login.updateUser(null);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            try (org.robolectric.android.controller.ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class).setup()) {
                ControlledPage page = new ControlledPage();
                android.os.Bundle args = new android.os.Bundle();
                args.putInt("TAGTYPE", com.maxwai.nclientv3.api.enums.TagType.CATEGORY.getId());
                page.setArguments(args);
                controller.get().getSupportFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                assertTrue(page.completions.size() == 1);
                AuthStore.saveApiKey(app, "second-fixture", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(page.completions.size() == 2);
                String identity = AuthStore.activeIdentity(app);
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    java.util.Collections.emptyList()));
                page.completions.get(1).onComplete(true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                android.widget.TextView status = page.requireView().findViewById(R.id.online_tag_status);
                assertTrue(status.getText().equals(app.getString(R.string.online_blacklist_loaded)));
                page.completions.get(0).onComplete(false);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(status.getText().equals(app.getString(R.string.online_blacklist_loaded)));
            }
        } finally {
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }
    @Test public void restoreReloadsOnceAfterPendingMutationFinishes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "restore-fixture", true);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity), java.util.Collections.emptyList()));
            try (org.robolectric.android.controller.ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class).setup()) {
                ControlledPage page = new ControlledPage();
                android.os.Bundle args = new android.os.Bundle();
                args.putInt("TAGTYPE", com.maxwai.nclientv3.api.enums.TagType.CATEGORY.getId());
                page.setArguments(args);
                controller.get().getSupportFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                long operation = Login.beginOnlineTagMutation(app, identity);
                assertTrue(operation > 0);
                Login.beginOnlineTagsRestore();
                Login.beginOnlineTagsRestore();
                Login.endOnlineTagsRestore();
                Login.endOnlineTagsRestore();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(page.completions.size() == 1);
                com.maxwai.nclientv3.api.components.Tag tag = new com.maxwai.nclientv3.api.components.Tag(
                    "fixture", 1, 4251, com.maxwai.nclientv3.api.enums.TagType.TAG, com.maxwai.nclientv3.api.enums.TagStatus.DEFAULT);
                Login.finishOnlineTagMutation(app, identity, operation, tag, true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(page.completions.size() == 2);
                page.completions.get(1).onComplete(false);
                page.completions.get(0).onComplete(false);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(page.completions.size() == 2);
            }
        } finally {
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }

    @Test public void pageOpenedDuringRestoreLoadsAfterRestoreFinishes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "restore-open-fixture", true);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            try (org.robolectric.android.controller.ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class).setup()) {
                Login.beginOnlineTagsRestore();
                try {
                    ControlledPage page = new ControlledPage();
                    android.os.Bundle args = new android.os.Bundle();
                    args.putInt("TAGTYPE", com.maxwai.nclientv3.api.enums.TagType.CATEGORY.getId());
                    page.setArguments(args);
                    controller.get().getSupportFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                    assertTrue(page.completions.isEmpty());
                    Login.endOnlineTagsRestore();
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    assertTrue(page.completions.size() == 1);
                    page.completions.get(0).onComplete(false);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    assertTrue(page.completions.size() == 1);
                } finally {
                    if (Login.isOnlineTagMutationPending()) Login.endOnlineTagsRestore();
                }
            }
        } finally {
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }

    @Test public void pageOpenedDuringMutationUsesCompletedData() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "mutation-open-fixture", true);
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            String identity = AuthStore.activeIdentity(app);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity), java.util.Collections.emptyList()));
            long operation = Login.beginOnlineTagMutation(app, identity);
            assertTrue(operation > 0);
            try (org.robolectric.android.controller.ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class).setup()) {
                ControlledPage page = new ControlledPage();
                android.os.Bundle args = new android.os.Bundle();
                args.putInt("TAGTYPE", com.maxwai.nclientv3.api.enums.TagType.CATEGORY.getId());
                page.setArguments(args);
                controller.get().getSupportFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                assertTrue(page.completions.isEmpty());
                com.maxwai.nclientv3.api.components.Tag tag = new com.maxwai.nclientv3.api.components.Tag(
                    "fixture", 1, 4251, com.maxwai.nclientv3.api.enums.TagType.TAG, com.maxwai.nclientv3.api.enums.TagStatus.DEFAULT);
                assertTrue(Login.finishOnlineTagMutation(app, identity, operation, tag, true));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(page.completions.isEmpty());
                assertTrue(page.requireView().findViewById(R.id.online_tag_refresh).isEnabled());
                assertTrue(page.requireView().findViewById(R.id.online_tag_progress).getVisibility() == android.view.View.GONE);
                android.widget.TextView status = page.requireView().findViewById(R.id.online_tag_status);
                assertTrue(status.getText().equals(app.getString(R.string.online_blacklist_loaded)));
            }
        } finally {
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }

    @Test public void authChangeRecreatesAdapterAndDestroyedViewIgnoresChanges() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        okhttp3.OkHttpClient previous = Global.client;
        Global.client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain ->
            new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(okhttp3.ResponseBody.create("{\"count\":0,\"tags\":[]}", null)).build()).build();
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            try (org.robolectric.android.controller.ActivityController<TagFilterActivity> controller =
                     Robolectric.buildActivity(TagFilterActivity.class).setup()) {
                TagFilterActivity activity = controller.get();
                TagTypePage page = TagTypePage.newInstance(6);
                activity.getSupportFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                RecyclerView recycler = page.getRecyclerView();
                RecyclerView.Adapter<?> first = recycler.getAdapter();
                AuthStore.saveApiKey(app, "fixture-key", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                RecyclerView.Adapter<?> second = recycler.getAdapter();
                assertNotSame(first, second);
                AuthStore.saveApiKey(app, "fixture-key", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertSame(second, recycler.getAdapter());
                activity.getSupportFragmentManager().beginTransaction().remove(page).commitNow();
                AuthStore.clear(app);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
        } finally {
            Global.client = previous;
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }
    @Test public void refreshStatusReflectsNewerAvailableData() {
        assertFalse(TagTypePage.refreshHasCurrentData(false, -1, -1));
        assertFalse(TagTypePage.refreshHasCurrentData(false, 4, 4));
        assertTrue(TagTypePage.refreshHasCurrentData(false, 4, 5));
        assertTrue(TagTypePage.refreshHasCurrentData(true, 4, 5));
        assertFalse(TagTypePage.refreshHasCurrentData(true, 4, -1));
    }
}
