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
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
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
                assertEquals(View.GONE, activity.findViewById(R.id.gallery_shell_language).getVisibility());
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
                assertEquals("Updated", activity.getTitle().toString());
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
