package com.theskyc.nreader.api;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Parcel;

import com.google.android.material.snackbar.Snackbar;
import com.theskyc.nreader.MainActivity;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.classes.Bookmark;
import com.theskyc.nreader.settings.ApiAuthInterceptor;
import com.theskyc.nreader.settings.AuthSessionStore;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class InspectorAccountTagsTest {
    private final InspectorV3.InspectorResponse response = new InspectorV3.DefaultInspectorResponse() {};
    private Application app;
    private DatabaseHelper helper;
    private SharedPreferences preferences;
    private String setting;
    private OkHttpClient previousClient;

    @Before public void installAccountTags() {
        app = RuntimeEnvironment.getApplication();
        previousClient = Global.client;
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        preferences = app.getSharedPreferences("Settings", 0);
        setting = app.getString(R.string.preference_key_use_account_tag);
        preferences.edit().putBoolean(setting, true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "account-search-fixture", true);
        helper = new DatabaseHelper(app);
        helper.getWritableDatabase();
        Queries.TagTable.updateTag(new Tag("local choice", 3, 51001, TagType.TAG, TagStatus.ACCEPTED));
        replaceBlacklist(Collections.singletonList(new Tag("account choice", 3, 51002, TagType.TAG, TagStatus.DEFAULT)));
    }

    @After public void cleanUp() {
        Global.client = previousClient;
        AuthStore.clear(app);
        Login.updateUser(null);
        preferences.edit().remove(setting).commit();
        Global.initFromShared(app);
        if (helper != null) helper.close();
        Queries.setDb(null);
    }

    @Test public void bookmarkOmitsAutomaticBlacklistTerms() {
        InspectorV3 live = search();
        assertTrue(query(live.getUrl()).contains("-tag:\"account choice\""));
        Queries.BookmarkTable.addBookmark(live);
        List<Bookmark> saved = Queries.BookmarkTable.getBookmarks();
        assertEquals(1, saved.size());
        assertEquals(3, saved.get(0).page);
        String portable = query(saved.get(0).url);
        assertTrue(portable.contains("fixture title"));
        assertTrue(portable.contains("tag:\"local choice\""));
        assertFalse(portable.contains("account choice"));
    }

    @Test public void staleBlacklistRevisionDoesNotDispatchSearch() {
        AtomicInteger requests = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Network should not be reached");
        }).build();
        InspectorV3 live = search();
        assertTrue(query(live.getUrl()).contains("-tag:\"account choice\""));
        InspectorV3 nextPage = live.cloneInspector(app, response);
        nextPage.setPage(4);
        replaceBlacklist(Collections.emptyList());

        assertThrows(IOException.class, nextPage::createDocument);
        assertEquals(0, requests.get());
    }

    @Test public void parcelRetainsOnlyThePortableQuery() {
        InspectorV3 live = search();
        Parcel parcel = Parcel.obtain();
        try {
            live.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            InspectorV3 restored = InspectorV3.CREATOR.createFromParcel(parcel);
            assertEquals(3, restored.getPage());
            assertTrue(query(restored.getUrl()).contains("tag:\"local choice\""));
            assertFalse(query(restored.getUrl()).contains("account choice"));
        } finally {
            parcel.recycle();
        }
    }

    @Test public void disablingAccountTagsInvalidatesExistingSearchWithoutActivityResume() {
        AtomicInteger requests = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Network should not be reached");
        }).build();
        InspectorV3 live = search();
        assertTrue(query(live.getUrl()).contains("-tag:\"account choice\""));

        preferences.edit().putBoolean(setting, false).commit();
        assertFalse(live.isAccountTagScopeCurrent());
        assertThrows(IOException.class, live::createDocument);
        assertEquals(0, requests.get());

        preferences.edit().putBoolean(setting, true).commit();
        assertFalse(live.isAccountTagScopeCurrent());
        assertTrue(search().isAccountTagScopeCurrent());
    }

    @Test public void enablingCachedBlacklistInvalidatesUnscopedHomeRequest() {
        preferences.edit().putBoolean(setting, false).commit();
        InspectorV3 unscoped = InspectorV3.basicInspector(app, 1, response);
        assertTrue(unscoped.usesCurrentDefaultAccountTags());
        assertFalse(query(unscoped.getUrl()).contains("account choice"));

        preferences.edit().putBoolean(setting, true).commit();
        assertFalse(unscoped.usesCurrentDefaultAccountTags());
        InspectorV3 rebuilt = InspectorV3.basicInspector(app, 1, response);
        assertTrue(rebuilt.usesCurrentDefaultAccountTags());
        assertTrue(query(rebuilt.getUrl()).contains("-tag:\"account choice\""));
    }

    @Test public void authDispatchRejectsBlacklistChangedAfterRequestConstruction() {
        AtomicInteger requests = new AtomicInteger();
        Global.client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                replaceBlacklist(Collections.emptyList());
                return chain.proceed(chain.request());
            })
            .addInterceptor(new ApiAuthInterceptor(app))
            .addInterceptor(chain -> {
                requests.incrementAndGet();
                throw new IOException("Network should not be reached");
            }).build();
        InspectorV3 live = search();
        assertTrue(query(live.getUrl()).contains("-tag:\"account choice\""));
        IOException error = assertThrows(IOException.class, live::createDocument);
        assertEquals("API operation expired", error.getMessage());
        assertEquals(0, requests.get());
    }

    @Test public void queuedSharedSearchResultIsDroppedAfterAccountSwitch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> sentQuery = new AtomicReference<>();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (!chain.request().url().encodedPath().endsWith("/search")) throw new IOException("Unexpected endpoint");
            sentQuery.set(chain.request().url().queryParameter("query"));
            int resultId = requests.incrementAndGet();
            String body = "{\"num_pages\":1,\"result\":[{\"id\":" + resultId
                + ",\"media_id\":\"21\",\"tag_ids\":[51001]}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(body, null)).build();
        }).build();
        boolean firstTime = ReflectionHelpers.getStaticField(MainActivity.class, "firstTime");
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", false);
        Intent intent = new Intent(app, MainActivity.class).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "fixture title");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            awaitSearch(activity);
            assertEquals(1, requests.get());
            assertTrue(sentQuery.get().contains("-tag:\"account choice\""));

            AuthStore.saveApiKey(app, "different-account-search-fixture", true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, activity.adapter.getItemCount());
            assertEquals(1, requests.get());

            Snackbar snackbar = ReflectionHelpers.getField(activity, "snackbar");
            assertTrue(snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action).performClick());
            awaitSearch(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, activity.adapter.getItemCount());
            assertEquals(2, requests.get());
            assertTrue(sentQuery.get().contains("tag:\"local choice\""));
            assertFalse(sentQuery.get().contains("account choice"));
        } finally {
            ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", firstTime);
        }
    }

    @Test public void homeBookmarkBindsCurrentRulesAndBlacklistAtLaunch() throws Exception {
        AtomicReference<HttpUrl> sent = new AtomicReference<>();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            sent.set(chain.request().url());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create("{\"result\":[]}", null)).build();
        }).build();
        Bookmark bookmark = new Bookmark("https://nhentai.net/api/v2/galleries?page=3", 3, ApiRequestType.BYALL, 0);
        InspectorV3 saved = bookmark.createInspector(app, response);
        assertEquals(ApiRequestType.BYALL, saved.getRequestType());
        replaceBlacklist(Collections.singletonList(new Tag("updated account choice", 3, 51003, TagType.TAG, TagStatus.DEFAULT)));
        String prefix = app.getPackageName();
        Intent intent = new Intent(app, MainActivity.class).putExtra(prefix + ".BYBOOKMARK", true)
            .putExtra(prefix + ".INSPECTOR", saved);
        boolean firstTime = ReflectionHelpers.getStaticField(MainActivity.class, "firstTime");
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", false);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            awaitSearch(controller.get());
            HttpUrl request = sent.get();
            assertEquals("3", request.queryParameter("page"));
            assertTrue(request.queryParameter("query").contains("tag:\"local choice\""));
            assertTrue(request.queryParameter("query").contains("-tag:\"updated account choice\""));
        } finally {
            ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", firstTime);
        }
    }

    private static void awaitSearch(MainActivity activity) throws InterruptedException {
        InspectorV3 request = ReflectionHelpers.getField(activity, "inspector");
        request.join(5000);
        assertFalse(request.isAlive());
    }

    private InspectorV3 search() {
        return InspectorV3.searchInspector(app, "fixture title", null, 3, SortType.RECENT_ALL_TIME, null, response);
    }

    private void replaceBlacklist(List<Tag> tags) {
        String identity = AuthStore.activeIdentity(app);
        assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity), tags));
    }

    private static String query(String url) {
        return HttpUrl.get(url).queryParameter("query");
    }
}
