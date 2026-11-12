package com.maxwai.nclientv3.adapters;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import com.maxwai.nclientv3.TagFilterActivity;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.AsyncCursorQuery;
import com.maxwai.nclientv3.async.database.Queries;
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
import org.robolectric.annotation.LooperMode;

import java.util.ArrayDeque;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TagsAdapterTest {
    @Test public void delayedQueryCannotPublishAfterBlacklistRevisionChanges() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "fixture-key", true);
        Login.updateUser(null);
        try (SQLiteDatabase db = SQLiteDatabase.create(null);
             ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class).create()) {
            Queries.setDb(db);
            java.lang.reflect.Field schema = Queries.TagTable.class.getDeclaredField("CREATE_TABLE");
            schema.setAccessible(true);
            db.execSQL((String) schema.get(null));
            String identity = AuthStore.activeIdentity(app);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.singletonList(tag)));
            ArrayDeque<Runnable> work = new ArrayDeque<>();
            TagsAdapter adapter = new TagsAdapter(controller.get(), "", true, new AsyncCursorQuery(work::add));
            try {
                work.remove().run();
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    Collections.emptyList()));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, adapter.getItemCount());
                adapter.filter("");
                work.remove().run();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, adapter.getItemCount());
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    Collections.singletonList(tag)));
                adapter.filter("");
                work.remove().run();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(1, adapter.getItemCount());
            } finally {
                adapter.close();
            }
        } finally {
            Queries.setDb(null);
            AuthStore.clear(app);
            Login.updateUser(null);
        }
    }
}
