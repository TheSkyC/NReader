// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.ContentValues;
import android.os.Looper;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import com.google.android.material.tabs.TabLayout;

import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.adapters.FavoriteAdapter;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.GalleryOpenGate;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.utility.Utility;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryOpeningTest {
    private static final String DETAIL = "{\"id\":117,\"media_id\":1,\"num_pages\":1,"
        + "\"title\":{\"pretty\":\"Test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
        + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
        + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}";

    @Test
    public void incompleteFavoriteOpensOneShellWithKnownMetadata() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        GalleryOpenGate.release();
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery original = new Gallery(application, DETAIL, null, false);
            Queries.FavoriteTable.addFavorite(new SimpleGallery(original));
            Gallery partial = Queries.GalleryTable.galleryFromId(application, 117);
            assertTrue(partial.getGalleryData().isIncomplete());
            try (ActivityController<FavoriteActivity> controller = Robolectric.buildActivity(FavoriteActivity.class).setup()) {
                FavoriteActivity activity = controller.get();
                FavoriteAdapter adapter = ReflectionHelpers.getField(activity, "adapter");
                ReflectionHelpers.callInstanceMethod(adapter, "startGallery",
                    ReflectionHelpers.ClassParameter.from(Gallery.class, partial));
                ReflectionHelpers.callInstanceMethod(adapter, "startGallery",
                    ReflectionHelpers.ClassParameter.from(Gallery.class, partial));
                Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
                assertTrue(opened != null);
                SimpleGallery shell = opened.getParcelableExtra(application.getPackageName() + ".GALLERY");
                assertEquals(117, shell.getId());
                assertEquals(original.getTitle(), shell.getTitle());
                assertEquals(original.getPageCount(), shell.getPageCount());
                assertEquals(null, Shadows.shadowOf(activity).getNextStartedActivity());
            }
        } finally {
            GalleryOpenGate.release();
        }
    }

    @Test
    public void cacheLookupRejectsLegacyAndTruncatedPageRecords() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
            Global.initFromShared(application);
            Queries.GalleryTable.insert(new Gallery(application, DETAIL, null, false));
            assertTrue(Queries.GalleryTable.completeCachedGalleryFromId(application, 117) != null);
            ContentValues values = new ContentValues();
            for (String pages : new String[]{"legacy", "2;/cover.png;/thumb.png;/1.png;", "partial-v1;1;cover"}) {
                values.put(Queries.GalleryTable.PAGES, pages);
                db.update(Queries.GalleryTable.TABLE_NAME, values, Queries.GalleryTable.IDGALLERY + "=?", new String[]{"117"});
                assertEquals(null, Queries.GalleryTable.completeCachedGalleryFromId(application, 117));
            }
            Queries.GalleryTable.insert(new Gallery(application, DETAIL, null, false));
            values.clear();
            values.put(Queries.GalleryTable.MEDIAID, 0);
            db.update(Queries.GalleryTable.TABLE_NAME, values, Queries.GalleryTable.IDGALLERY + "=?", new String[]{"117"});
            assertEquals(null, Queries.GalleryTable.completeCachedGalleryFromId(application, 117));
        }
    }

    @Test
    public void shellDisablesActionsUntilDetailArrives() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Gallery full = new Gallery(application, DETAIL, null, false);
            String withLanguage = DETAIL.replace("\"num_pages\":1,",
                "\"num_pages\":1,\"tags\":[{\"id\":12227,\"count\":1,\"name\":\"english\",\"type\":\"language\"}],");
            Gallery english = new Gallery(application, withLanguage, null, false);
            assertEquals(com.maxwai.nclientv3.api.enums.Language.ENGLISH, new SimpleGallery(english).getLanguage());
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(full));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Test", activity.getTitle().toString());
                assertFalse(activity.findViewById(R.id.gallery_read).isEnabled());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_shell_language).getVisibility());
                assertEquals("ID 117", ((android.widget.TextView) activity.findViewById(R.id.id_num)).getText().toString());
                activity.findViewById(R.id.id_num).performClick();
                android.content.ClipboardManager clipboard = activity.getSystemService(android.content.ClipboardManager.class);
                assertEquals("117", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                Menu menu = ((Toolbar) activity.findViewById(R.id.toolbar)).getMenu();
                activity.onCreateOptionsMenu(menu);
                assertFalse(menu.findItem(R.id.manage_status).isVisible());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_metadata).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.favorite_stat).getVisibility());
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(1).select();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_pages_status).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.refresher).getVisibility());
                activity.onCreateOptionsMenu(menu);
                assertFalse(menu.findItem(R.id.change_view).isVisible());

                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_shell_language).getVisibility());
                assertEquals("ID 117", ((android.widget.TextView) activity.findViewById(R.id.id_num)).getText().toString());
                activity.findViewById(R.id.id_num).performClick();
                assertEquals("117", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.gallery_pages_status).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.refresher).getVisibility());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void listTitleSurvivesCacheRefreshAndRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                String refreshed = DETAIL.replace("\"pretty\":\"Test\"", "\"pretty\":\"Updated\"");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(refreshed, null)).build();
            }).build();
            String title = "[Group] Test [English]";
            SimpleGallery shell = SimpleGallery.fromV2ListItem(application, new JSONObject()
                .put("id", 117).put("media_id", "1").put("english_title", title).put("num_pages", 1));
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", shell);
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals("Test", ((Gallery) ReflectionHelpers.getField(activity, "gallery")).getTitle());
                assertEquals(title, activity.getTitle().toString());
                assertEquals(title, ((android.widget.TextView) activity.findViewById(R.id.gallery_title)).getText().toString());

                release.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!"Updated".equals(((Gallery) ReflectionHelpers.getField(activity, "gallery")).getTitle())
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals("Updated", ((Gallery) ReflectionHelpers.getField(activity, "gallery")).getTitle());
                assertEquals(title, activity.getTitle().toString());
                assertEquals(title, ((android.widget.TextView) activity.findViewById(R.id.gallery_title)).getText().toString());
                activity.findViewById(R.id.toolbar).performLongClick();
                android.content.ClipboardManager clipboard = activity.getSystemService(android.content.ClipboardManager.class);
                assertEquals(title, clipboard.getPrimaryClip().getItemAt(0).getText().toString());

                controller.recreate();
                GalleryActivity recreated = controller.get();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(title, recreated.getTitle().toString());
                assertEquals(title, ((android.widget.TextView) recreated.findViewById(R.id.gallery_title)).getText().toString());
                recreated.findViewById(R.id.toolbar).performLongClick();
                assertEquals(title, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void entriesWithoutListTitleUsePreferredDetailTitle() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        com.maxwai.nclientv3.api.enums.TitleType previousTitleType = Global.getTitleType();
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            ReflectionHelpers.setStaticField(Global.class, "titleType", com.maxwai.nclientv3.api.enums.TitleType.ENGLISH);
            String detail = DETAIL.replace("\"pretty\":\"Test\"", "\"pretty\":\"Test\",\"english\":\"English title\"");
            Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(detail, null)).build()).build();
            for (String entryTitle : new String[]{null, "", " \t "}) {
                Intent intent = new Intent(application, GalleryActivity.class);
                intent.putExtra(application.getPackageName() + ".GALLERY", entryTitle == null
                    ? new Gallery(application, detail, null, false)
                    : SimpleGallery.fromV2ListItem(application, new JSONObject()
                        .put("id", 117).put("media_id", "1").put("english_title", entryTitle).put("num_pages", 1)));
                try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                    GalleryActivity activity = controller.get();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                        && System.nanoTime() < deadline) {
                        Shadows.shadowOf(Looper.getMainLooper()).idle();
                        Thread.sleep(10);
                    }
                    assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                    assertEquals("English title", activity.getTitle().toString());
                    assertEquals("English title", ((android.widget.TextView) activity.findViewById(R.id.gallery_title)).getText().toString());
                }
            }
        } finally {
            Global.client = previous;
            ReflectionHelpers.setStaticField(Global.class, "titleType", previousTitleType);
        }
    }

    @Test
    public void cachedDetailRestoresPendingPositionAfterProgressLoads() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                throw new IOException("Offline");
            }).build();
            Gallery cached = new Gallery(application, DETAIL, null, false);
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            Bundle state = new Bundle();
            state.putBoolean("continuousReader", true);
            state.putInt("readerPage", 1);
            state.putFloat("readerOffset", 0.25f);
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup(state)) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "positionReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "positionReady"));
                assertFalse(ReflectionHelpers.getField(activity, "detailReady"));
                assertTrue(ReflectionHelpers.getField(activity, "pendingPosition") != null);
                Queries.GalleryTable.insert(cached);
                long generation = ReflectionHelpers.getField(activity, "detailRequestGeneration");
                ReflectionHelpers.callInstanceMethod(activity, "loadCachedDetail",
                    ReflectionHelpers.ClassParameter.from(int.class, 117),
                    ReflectionHelpers.ClassParameter.from(long.class, generation));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(null, ReflectionHelpers.getField(activity, "pendingPosition"));
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void refreshWaitsForReaderToCloseAndPreservesTagExpansion() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                String refreshed = DETAIL.replace("\"pretty\":\"Test\"", "\"pretty\":\"Updated\"");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(refreshed, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                Object oldAdapter = ReflectionHelpers.getField(activity, "detailsAdapter");
                boolean[] expanded = ReflectionHelpers.getField(oldAdapter, "expandedTags");
                expanded[0] = true;
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(1).select();
                controller.pause();
                release.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ReflectionHelpers.getField(activity, "pendingDetailRefresh") == null
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "pendingDetailRefresh") != null);
                assertEquals("Test", activity.getTitle().toString());
                controller.resume();
                assertTrue(ReflectionHelpers.getField(activity, "pendingDetailRefresh") != null);
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(0).select();
                assertEquals("Test", activity.getTitle().toString());
                assertEquals("Updated", ((Gallery) ReflectionHelpers.getField(activity, "gallery")).getTitle());
                assertEquals(null, ReflectionHelpers.getField(activity, "pendingDetailRefresh"));
                Object newAdapter = ReflectionHelpers.getField(activity, "detailsAdapter");
                boolean[] refreshedExpansion = ReflectionHelpers.getField(newAdapter, "expandedTags");
                assertTrue(refreshedExpansion[0]);
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void invalidNetworkRefreshDoesNotDiscardDisplayedCache() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            com.maxwai.nclientv3.async.database.Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                String wrongId = DETAIL.replace("\"id\":117", "\"id\":118");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(wrongId, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                release.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ReflectionHelpers.getField(activity, "detailRequest") != null
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(117, ((Gallery) ReflectionHelpers.getField(activity, "gallery")).getId());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void cacheArrivingAfterNetworkFailureKeepsRetryAvailable() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("offline")
                .body(ResponseBody.create("{}", null)).build()).build();
            Gallery cached = new Gallery(application, DETAIL, null, false);
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailRefreshFailed"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailRefreshFailed"));
                assertFalse(ReflectionHelpers.getField(activity, "detailReady"));
                Queries.GalleryTable.insert(cached);
                long generation = ReflectionHelpers.getField(activity, "detailRequestGeneration");
                ReflectionHelpers.callInstanceMethod(activity, "loadCachedDetail",
                    ReflectionHelpers.ClassParameter.from(int.class, 117),
                    ReflectionHelpers.ClassParameter.from(long.class, generation));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_error).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_retry).getVisibility());
                assertTrue(activity.findViewById(R.id.gallery_read).isEnabled());
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void cachedDetailSurvivesFailedNetworkRefresh() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            com.maxwai.nclientv3.async.database.Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                if (requests.incrementAndGet() > 1) {
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("offline").body(ResponseBody.create("{}", null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                View error = activity.findViewById(R.id.gallery_load_error);
                while ((!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    || error == null || error.getVisibility() != View.VISIBLE)
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                    error = activity.findViewById(R.id.gallery_load_error);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(View.VISIBLE, error.getVisibility());
                assertTrue(activity.findViewById(R.id.gallery_read).isEnabled());
                activity.findViewById(R.id.gallery_load_retry).performClick();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((requests.get() < 2 || ReflectionHelpers.getField(activity, "detailRequest") != null)
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(2, requests.get());
                assertEquals(View.GONE, activity.findViewById(R.id.gallery_load_error).getVisibility());
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void completeLocalCacheAppearsBeforeNetworkRefresh() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            com.maxwai.nclientv3.async.database.Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals("Test", activity.getTitle().toString());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void destroyingDetailCancelsRelatedTagRequest() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch relatedEntered = new CountDownLatch(1), releaseRelated = new CountDownLatch(1);
        AtomicReference<Call> relatedCall = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        String detailWithRelated = DETAIL.substring(0, DETAIL.length() - 1)
            + ",\"related\":[{\"id\":118,\"media_id\":\"2\",\"thumbnail\":\"galleries/2/thumb.jpg\","
            + "\"english_title\":\"Related\",\"num_pages\":1,\"tag_ids\":[12345]}]}";
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean tags = chain.request().url().encodedPath().endsWith("/tags/ids");
                if (tags) {
                    relatedCall.set(chain.call());
                    relatedEntered.countDown();
                    try {
                        if (!releaseRelated.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                String body = tags ? "[{\"id\":12345,\"name\":\"artist\",\"type\":\"artist\",\"count\":1}]"
                    : detailWithRelated;
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(body, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup();
            try {
                GalleryActivity activity = controller.get();
                assertTrue(relatedEntered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Gallery gallery = ReflectionHelpers.getField(activity, "gallery");
                Object subscription = ReflectionHelpers.getField(activity, "detailRequest");
                assertTrue(subscription != null);
                Object entry = ReflectionHelpers.getField(subscription, "entry");
                Thread inspector = ReflectionHelpers.getField(entry, "inspector");
                controller.pause().stop().destroy();
                assertTrue(relatedCall.get().isCanceled());
                releaseRelated.countDown();
                inspector.join(5000);
                assertFalse(inspector.isAlive());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(gallery.getRelated().isEmpty());
            } finally {
                releaseRelated.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void mainDetailIsReadyWhileRelatedTagsAreStillLoading() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch relatedEntered = new CountDownLatch(1);
        CountDownLatch releaseRelated = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        String detailWithRelated = DETAIL.substring(0, DETAIL.length() - 1)
            + ",\"related\":[{}, {\"id\":118,\"media_id\":\"2\",\"thumbnail\":\"galleries/2/thumb.jpg\","
            + "\"english_title\":\"Related\",\"num_pages\":1,\"tag_ids\":[12345]}]}";
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean tags = chain.request().url().encodedPath().endsWith("/tags/ids");
                if (tags) {
                    relatedEntered.countDown();
                    try {
                        if (!releaseRelated.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                String body = tags ? "[{\"id\":12345,\"name\":\"test artist\",\"type\":\"artist\",\"count\":1}]"
                    : detailWithRelated;
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(body, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(relatedEntered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                Gallery gallery = ReflectionHelpers.getField(activity, "gallery");
                assertTrue(gallery.getRelated().isEmpty());
                releaseRelated.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (gallery.getRelated().isEmpty() && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals(1, gallery.getRelated().size());
                assertEquals(118, gallery.getRelated().get(0).getId());
            } finally {
                releaseRelated.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void invalidDetailKeepsRetryVisibleAndRetryLoadsCorrectGallery() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                String response = requests.incrementAndGet() == 1
                    ? DETAIL.replace("\"id\":117", "\"id\":118") : DETAIL;
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(response, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                View error = activity.findViewById(R.id.gallery_load_error);
                while ((requests.get() == 0 || error == null || error.getVisibility() != View.VISIBLE)
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                    error = activity.findViewById(R.id.gallery_load_error);
                }
                assertEquals(View.VISIBLE, error.getVisibility());
                assertFalse(activity.findViewById(R.id.gallery_read).isEnabled());
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(1).select();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_pages_retry).getVisibility());
                activity.findViewById(R.id.gallery_pages_retry).performClick();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(2, requests.get());
                assertEquals(117, ((Gallery) ReflectionHelpers.getField(activity, "gallery")).getId());
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void accountChangeDiscardsRefreshPendingWhileReading() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            AuthStore.clear(application);
            Gallery cached = new Gallery(application, DETAIL, null, false);
            Queries.GalleryTable.insert(cached);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                String refreshed = DETAIL.replace("\"pretty\":\"Test\"", "\"pretty\":\"Updated\"");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(refreshed, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(cached));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(1).select();
                release.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ReflectionHelpers.getField(activity, "pendingDetailRefresh") == null
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "pendingDetailRefresh") != null);
                AuthStore.saveApiKey(application, "test-identity", true);
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(0).select();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Test", activity.getTitle().toString());
                assertEquals(null, ReflectionHelpers.getField(activity, "pendingDetailRefresh"));
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_error).getVisibility());
            }
        } finally {
            release.countDown();
            AuthStore.clear(application);
            Global.client = previous;
        }
    }

    @Test
    public void downloadedFallbackOpensOnlyOnExplicitActionAndKeepsCurrentPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("offline").body(ResponseBody.create("{}", null)).build()).build();
            File folder = new File(application.getCacheDir(), "fallback-test");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            File page = new File(folder, "1.jpg");
            assertTrue(page.isFile() || page.createNewFile());
            LocalGallery local = new LocalGallery(folder, true);
            assertTrue(local.isValid());
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailRefreshFailed"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailRefreshFailed"));
                ReflectionHelpers.setField(activity, "localFallbackGallery", local);
                ReflectionHelpers.setField(activity, "localFallbackIdentity", AuthStore.identityGeneration());
                ReflectionHelpers.setField(activity, "localFallbackSource", Utility.getBaseUrl());
                ReflectionHelpers.setField(activity, "localFallbackMirrorGeneration", Global.getMirrorGeneration());
                ReflectionHelpers.callInstanceMethod(activity, "detailLoadingState",
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
                assertEquals(null, Shadows.shadowOf(activity).getNextStartedActivity());
                View open = activity.findViewById(R.id.gallery_pages_local);
                assertEquals(View.VISIBLE, open.getVisibility());
                open.performClick();
                open.performClick();
                Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
                assertTrue(opened != null);
                assertTrue(opened.getBooleanExtra(application.getPackageName() + ".ISLOCAL", false));
                assertEquals(null, Shadows.shadowOf(activity).getNextStartedActivity());
                assertFalse(activity.isFinishing());
                controller.pause().resume();
                open.performClick();
                assertTrue(Shadows.shadowOf(activity).getNextStartedActivity() != null);
            }
        } finally {
            GalleryOpenGate.release();
            Global.client = previous;
        }
    }

    @Test
    public void accountChangeAfterFailureAllowsRetryWithCurrentIdentity() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            AuthStore.clear(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(call == 1 ? 503 : 200).message("test")
                    .body(ResponseBody.create(call == 1 ? "{}" : DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Object subscription = ReflectionHelpers.getField(activity, "detailRequest");
                Object entry = ReflectionHelpers.getField(subscription, "entry");
                Thread inspector = ReflectionHelpers.getField(entry, "inspector");
                ExecutorService delivery = ReflectionHelpers.getField(entry, "delivery");
                release.countDown();
                inspector.join(5000);
                assertTrue(delivery.awaitTermination(5, TimeUnit.SECONDS));
                AuthStore.saveApiKey(application, "test-identity", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_error).getVisibility());
                assertEquals(null, Shadows.shadowOf(activity).getNextStartedActivity());
                activity.retryDetailLoading();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(2, calls.get());
            }
        } finally {
            release.countDown();
            AuthStore.clear(application);
            Global.client = previous;
        }
    }

    @Test
    public void accountChangeFailureCancelsBlockedRelatedRequest() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch relatedEntered = new CountDownLatch(1), releaseRelated = new CountDownLatch(1);
        AtomicReference<Call> relatedCall = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        String detailWithRelated = DETAIL.substring(0, DETAIL.length() - 1)
            + ",\"related\":[{\"id\":118,\"media_id\":\"2\",\"thumbnail\":\"galleries/2/thumb.jpg\","
            + "\"english_title\":\"Related\",\"num_pages\":1,\"tag_ids\":[12345]}]}";
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            AuthStore.clear(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean tags = chain.request().url().encodedPath().endsWith("/tags/ids");
                if (tags) {
                    relatedCall.set(chain.call());
                    relatedEntered.countDown();
                    try {
                        if (!releaseRelated.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(tags ? "[]" : detailWithRelated, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(relatedEntered.await(5, TimeUnit.SECONDS));
                Object subscription = ReflectionHelpers.getField(activity, "detailRequest");
                com.maxwai.nclientv3.api.GalleryDetailRequests.Callback callback =
                    ReflectionHelpers.getField(subscription, "callback");
                AuthStore.saveApiKey(application, "test-identity", true);
                callback.onFailure(new IOException("Account identity changed"));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(relatedCall.get().isCanceled());
                assertEquals(null, ReflectionHelpers.getField(activity, "detailRequest"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_error).getVisibility());
            }
        } finally {
            releaseRelated.countDown();
            AuthStore.clear(application);
            Global.client = previous;
        }
    }

    @Test
    public void accountChangeBeforeUiDeliveryRejectsDetail() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            AuthStore.clear(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Object subscription = ReflectionHelpers.getField(activity, "detailRequest");
                Object entry = ReflectionHelpers.getField(subscription, "entry");
                Thread inspector = ReflectionHelpers.getField(entry, "inspector");
                ExecutorService delivery = ReflectionHelpers.getField(entry, "delivery");
                release.countDown();
                inspector.join(5000);
                assertFalse(inspector.isAlive());
                assertTrue(delivery.awaitTermination(5, TimeUnit.SECONDS));
                AuthStore.saveApiKey(application, "test-identity", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(ReflectionHelpers.getField(activity, "detailReady"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_load_error).getVisibility());
            }
        } finally {
            release.countDown();
            AuthStore.clear(application);
            Global.client = previous;
        }
    }

    @Test
    public void twoDetailActivitiesShareRequestWhenOneCloses() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Call> call = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                requests.incrementAndGet();
                call.set(chain.call());
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            ActivityController<GalleryActivity> first = Robolectric.buildActivity(GalleryActivity.class, intent).setup();
            try (ActivityController<GalleryActivity> second = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                first.pause().stop().destroy();
                assertFalse(call.get().isCanceled());
                release.countDown();
                GalleryActivity remaining = second.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(remaining, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(remaining, "detailReady"));
                assertEquals(1, requests.get());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void destroyingShellCancelsDetailRequest() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Call> call = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                call.set(chain.call());
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                controller.pause().stop().destroy();
                assertTrue(call.get().isCanceled());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }
}
