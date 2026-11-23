package com.theskyc.nreader.adapters;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.theskyc.nreader.TagFilterActivity;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.TagRowPages;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagDatabaseFixture;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.AccountRuntime;
import com.theskyc.nreader.settings.AuthSessionStore;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class TagsAdapterTest {
    @Test public void recycledHolderCannotActOnTheOldRowWhileItsPageLoads() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null);
             ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class).create()) {
            Queries.setDb(db);
            TagDatabaseFixture.createTables(db);
            for (int index = 0; index < 80; index++) {
                Tag tag = new Tag("tag-" + index, 100 - index, index + 1, TagType.TAG, TagStatus.ACCEPTED);
                Queries.TagTable.insert(tag);
                assertEquals(1, Queries.TagTable.updateTag(tag));
            }
            ArrayDeque<Runnable> work = new ArrayDeque<>();
            TagsAdapter adapter = new TagsAdapter(controller.get(), "", false, new TagRowPages(work::add));
            try {
                work.remove().run();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                TagsAdapter.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(controller.get()), 0);
                adapter.onBindViewHolder(holder, 0);
                String former = holder.title.getText().toString();
                org.junit.Assert.assertTrue(holder.master.isClickable());

                adapter.onBindViewHolder(holder, 70);
                assertEquals("", holder.title.getText().toString());
                org.junit.Assert.assertFalse(holder.master.isClickable());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    holder.master.getImportantForAccessibility());

                work.remove().run();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                adapter.onBindViewHolder(holder, 70);
                org.junit.Assert.assertTrue(holder.master.isClickable());
                org.junit.Assert.assertNotEquals(former, holder.title.getText().toString());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, holder.master.getImportantForAccessibility());
            } finally {
                adapter.close();
                while (!work.isEmpty()) work.remove().run();
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void delayedQueryCannotPublishAfterBlacklistRevisionChanges() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Field runtimeRepository = AccountRuntime.class.getDeclaredField("repository");
        runtimeRepository.setAccessible(true);
        Object previousRepository = runtimeRepository.get(null);
        runtimeRepository.set(null, null);
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        Login.initLogin(app);
        AuthStore.saveApiKey(app, "fixture-key", true);
        Login.updateUser(null);
        try (SQLiteDatabase db = SQLiteDatabase.create(null);
             ActivityController<TagFilterActivity> controller = Robolectric.buildActivity(TagFilterActivity.class).create()) {
            Queries.setDb(db);
            TagDatabaseFixture.createTables(db);
            String identity = AuthStore.activeIdentity(app);
            Tag tag = new Tag("example", 1, 42, TagType.TAG, TagStatus.DEFAULT);
            assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                Collections.singletonList(tag)));
            String pack = "{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
                + "\"entries\":[{\"id\":42,\"name\":\"translated name\"}]}";
            assertTrue(TagTranslationCatalog.apply(db, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                app.getCacheDir(), () -> false));
            try (Cursor translated = Queries.TagTable.getFilterCursor("translated", null, true, false,
                controller.get().getResources().getConfiguration().getLocales().get(0))) {
                assertEquals(1, translated.getCount());
            }
            ArrayDeque<Runnable> work = new ArrayDeque<>();
            TagsAdapter adapter = new TagsAdapter(controller.get(), "translated", true, new TagRowPages(work::add));
            try {
                work.remove().run();
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    Collections.emptyList()));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, adapter.getItemCount());
                adapter.filter("translated");
                work.remove().run();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, adapter.getItemCount());
                assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity),
                    Collections.singletonList(tag)));
                adapter.filter("translated");
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
            runtimeRepository.set(null, previousRepository);
        }
    }
}
