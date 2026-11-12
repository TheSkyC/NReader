package com.maxwai.nclientv3;

import android.app.Application;
import android.os.Looper;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.settings.AuthSessionStore;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityRequestTest {
    @Test public void restoreDuringBlacklistLoadRetriesAfterOldRequestFinishes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "restore-load-fixture", true);
        com.maxwai.nclientv3.settings.Login.initLogin(app);
        OkHttpClient previous = Global.client;
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger blacklistCalls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean blacklist = chain.request().url().encodedPath().endsWith("/blacklist");
            if (blacklist && blacklistCalls.incrementAndGet() == 1) {
                first.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            } else if (blacklist) second.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(blacklist
                    ? "{\"count\":0,\"tags\":[]}" : "{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(first.await(5, TimeUnit.SECONDS));
                com.maxwai.nclientv3.settings.Login.beginOnlineTagsRestore();
                com.maxwai.nclientv3.settings.Login.endOnlineTagsRestore();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (second.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, second.getCount());
            }
        } finally {
            release.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void staleBlacklistLoadWaitsForConcurrentMutation() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "load-mutation-fixture", true);
        com.maxwai.nclientv3.settings.Login.initLogin(app);
        OkHttpClient previous = Global.client;
        CountDownLatch loadEntered = new CountDownLatch(1), releaseLoad = new CountDownLatch(1);
        CountDownLatch feedAfterMutation = new CountDownLatch(1);
        AtomicInteger feedCalls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean blacklist = chain.request().url().encodedPath().endsWith("/blacklist");
            if (blacklist) {
                loadEntered.countDown();
                try {
                    if (!releaseLoad.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            } else if (feedCalls.incrementAndGet() > 1) feedAfterMutation.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(blacklist
                    ? "{\"count\":0,\"tags\":[]}"
                    : "{\"num_pages\":1,\"result\":[{\"id\":42,\"media_id\":\"1\",\"num_pages\":1}]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            String identity = AuthStore.activeIdentity(app);
            assertTrue(com.maxwai.nclientv3.settings.Login.replaceOnlineTags(app, identity,
                com.maxwai.nclientv3.settings.Login.beginOnlineTagsLoad(app, identity), java.util.Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("inspector");
                field.setAccessible(true);
                java.lang.reflect.Method load = MainActivity.class.getDeclaredMethod("loadInitialOnlineTags", InspectorV3.class);
                load.setAccessible(true);
                load.invoke(activity, field.get(activity));
                assertTrue(loadEntered.await(5, TimeUnit.SECONDS));
                long operation = com.maxwai.nclientv3.settings.Login.beginOnlineTagMutation(app, identity);
                assertTrue(operation > 0);
                releaseLoad.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (fieldValue(activity, "blacklistLoadingInspector") != null && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertNull(fieldValue(activity, "blacklistLoadingInspector"));
                assertTrue(((SwipeRefreshLayout) activity.findViewById(R.id.refresher)).isRefreshing());
                com.google.android.material.snackbar.Snackbar shown =
                    (com.google.android.material.snackbar.Snackbar) fieldValue(activity, "snackbar");
                assertTrue(shown == null || !shown.isShown());
                com.maxwai.nclientv3.api.components.Tag tag = new com.maxwai.nclientv3.api.components.Tag(
                    "fixture", 1, 4251, com.maxwai.nclientv3.api.enums.TagType.TAG,
                    com.maxwai.nclientv3.api.enums.TagStatus.DEFAULT);
                assertTrue(com.maxwai.nclientv3.settings.Login.finishOnlineTagMutation(app, identity, operation, tag, true));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (feedAfterMutation.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, feedAfterMutation.getCount());
            }
        } finally {
            releaseLoad.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    private static Object fieldValue(MainActivity activity, String name) throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    @Test public void feedWaitsForPendingBlacklistMutation() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "pending-mutation-fixture", true);
        com.maxwai.nclientv3.settings.Login.initLogin(app);
        OkHttpClient previous = Global.client;
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch feed = new CountDownLatch(1);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            feed.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            String identity = AuthStore.activeIdentity(app);
            com.maxwai.nclientv3.api.components.Tag tag = new com.maxwai.nclientv3.api.components.Tag(
                "pending-fixture", 1, 4250, com.maxwai.nclientv3.api.enums.TagType.TAG,
                com.maxwai.nclientv3.api.enums.TagStatus.DEFAULT);
            assertTrue(com.maxwai.nclientv3.settings.Login.replaceOnlineTags(app, identity,
                com.maxwai.nclientv3.settings.Login.beginOnlineTagsLoad(app, identity), java.util.Collections.emptyList()));
            long operation = com.maxwai.nclientv3.settings.Login.beginOnlineTagMutation(app, identity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, requests.get());
                assertTrue(com.maxwai.nclientv3.settings.Login.finishOnlineTagMutation(app, identity, operation, tag, true));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (feed.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, feed.getCount());
            }
        } finally {
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void favoritePageClearsOldAccountAndLoadsNewAccount() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "favorite-first-fixture", true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        OkHttpClient previous = Global.client;
        CountDownLatch secondEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch filterBlocked = new CountDownLatch(1), releaseFilter = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean switched = new java.util.concurrent.atomic.AtomicBoolean();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (!chain.request().url().encodedPath().endsWith("/favorites")) {
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(404).message("fixture").body(ResponseBody.create("{}", null)).build();
            }
            int number = calls.incrementAndGet();
            if (switched.get()) {
                secondEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            }
            String body = "{\"num_pages\":1,\"result\":[{\"id\":" + number
                + ",\"media_id\":\"1\",\"num_pages\":1}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(body, null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            android.content.Intent intent = new android.content.Intent(app, MainActivity.class)
                .putExtra(app.getPackageName() + ".FAVORITE", true);
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class, intent).setup()) {
                MainActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (activity.adapter.getItemCount() == 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(1, activity.adapter.getItemCount());
                java.lang.reflect.Field executorField = activity.adapter.getClass().getDeclaredField("filterExecutor");
                executorField.setAccessible(true);
                java.util.concurrent.ExecutorService executor =
                    (java.util.concurrent.ExecutorService) executorField.get(activity.adapter);
                executor.execute(() -> {
                    filterBlocked.countDown();
                    try { releaseFilter.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
                assertTrue(filterBlocked.await(5, TimeUnit.SECONDS));
                activity.adapter.setLoadedFilters(false, false, 2, false);
                switched.set(true);
                AuthStore.saveApiKey(app, "favorite-second-fixture", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, activity.adapter.getItemCount());
                releaseFilter.countDown();
                executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, activity.adapter.getItemCount());
                release.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (activity.adapter.getItemCount() == 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(1, activity.adapter.getItemCount());
            }
        } finally {
            release.countDown();
            releaseFilter.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
        }
    }

    @Test public void switchingIdentityDuringBlacklistLoadStartsNewLoad() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "first-fixture", true);
        com.maxwai.nclientv3.settings.Login.updateUser(null);
        OkHttpClient previous = Global.client;
        CountDownLatch firstEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1), galleryEntered = new CountDownLatch(1);
        AtomicInteger blacklistCalls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean blacklist = chain.request().url().encodedPath().endsWith("/blacklist");
            int number = blacklist ? blacklistCalls.incrementAndGet() : 0;
            if (number == 1) {
                firstEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            } else if (blacklist) secondEntered.countDown();
            else galleryEntered.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(number == 1 ? 500 : 200).message("fixture")
                .body(ResponseBody.create(blacklist ? "{\"count\":0,\"tags\":[]}"
                    : "{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                AuthStore.saveApiKey(app, "second-fixture", true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (galleryEntered.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, secondEntered.getCount());
                assertEquals(0, galleryEntered.getCount());
            }
        } finally {
            release.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void changedBlacklistReplacesInFlightFeed() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "revision-fixture", true);
        com.maxwai.nclientv3.settings.Login.initLogin(app);
        OkHttpClient previous = Global.client;
        CountDownLatch firstEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            int number = calls.incrementAndGet();
            if (number == 1) {
                firstEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            } else secondEntered.countDown();
            String body = "{\"num_pages\":1,\"result\":[{\"id\":" + number
                + ",\"media_id\":\"1\",\"num_pages\":1}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(body, null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            String identity = AuthStore.activeIdentity(app);
            long revision = com.maxwai.nclientv3.settings.Login.beginOnlineTagsLoad(app, identity);
            assertTrue(com.maxwai.nclientv3.settings.Login.replaceOnlineTags(app, identity, revision,
                java.util.Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                revision = com.maxwai.nclientv3.settings.Login.beginOnlineTagsLoad(app, identity);
                assertTrue(com.maxwai.nclientv3.settings.Login.replaceOnlineTags(app, identity, revision,
                    java.util.Collections.emptyList()));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                release.countDown();
            }
        } finally {
            release.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void removingCredentialsDuringBlacklistLoadResumesFeed() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "removed-fixture", true);
        com.maxwai.nclientv3.settings.Login.updateUser(null);
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), gallery = new CountDownLatch(1);
        AtomicInteger blacklistCalls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean blacklist = chain.request().url().encodedPath().endsWith("/blacklist");
            if (blacklist) {
                blacklistCalls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            } else gallery.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(blacklist ? 500 : 200).message("fixture")
                .body(ResponseBody.create(blacklist ? "{}" : "{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                java.lang.reflect.Method start = MainActivity.class.getDeclaredMethod("startInspector");
                start.setAccessible(true);
                start.invoke(controller.get());
                assertEquals(1, blacklistCalls.get());
                AuthStore.clear(app);
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (gallery.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, gallery.getCount());
            }
        } finally {
            release.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void queuedOldResultCannotReplaceNewRequestData() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        CountDownLatch firstEntered = new CountDownLatch(1), firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            int number = calls.incrementAndGet();
            if (number == 1 || number == 2) {
                (number == 1 ? firstEntered : secondEntered).countDown();
                try {
                    if (!(number == 1 ? firstRelease : secondRelease).await(5, TimeUnit.SECONDS))
                        throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
            }
            String body = "{\"num_pages\":1,\"result\":[{\"id\":" + number
                + ",\"media_id\":\"1\",\"num_pages\":1}]}";
            Response response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(body, null)).build();
            return response;
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("inspector");
                field.setAccessible(true);
                InspectorV3 first = (InspectorV3) field.get(activity);
                firstRelease.countDown();
                first.join(5000);
                assertFalse(first.isAlive());
                InspectorV3 second = first.cloneInspector(activity, first.getResponse());
                field.set(activity, second);
                java.lang.reflect.Method start = MainActivity.class.getDeclaredMethod("startInspector");
                start.setAccessible(true);
                start.invoke(activity);
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, activity.adapter.getItemCount());
                secondRelease.countDown();
                second.join(5000);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(1, activity.adapter.getItemCount());
            }
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
        }
    }

    @Test public void expiredSessionOffersSignInWithoutLoadingUnfilteredFeed() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        assertTrue(AuthSessionStore.replace(app, AuthSessionStore.tokenCookies("access-fixture", "refresh-fixture"), null));
        app.getSharedPreferences("Auth", 0).edit().putBoolean("session_rejected", true).commit();
        com.maxwai.nclientv3.settings.Login.initLogin(app);
        OkHttpClient previous = Global.client;
        AtomicInteger requests = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new java.io.IOException("Unexpected request");
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (activity.findViewById(com.google.android.material.R.id.snackbar_action) == null
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                android.widget.TextView message = activity.findViewById(com.google.android.material.R.id.snackbar_text);
                android.widget.TextView action = activity.findViewById(com.google.android.material.R.id.snackbar_action);
                assertNotNull(message);
                assertNotNull(action);
                assertEquals(activity.getString(R.string.online_blacklist_session_expired), message.getText().toString());
                assertEquals(activity.getString(R.string.account_sign_in), action.getText().toString());
                assertEquals(0, requests.get());
                action.performClick();
                assertEquals(AccountLoginActivity.class.getName(),
                    Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            }
        } finally {
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthSessionStore.clear(app);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void failedBlacklistBlocksFeedAndRetryLoadsIt() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "retry-fixture", true);
        com.maxwai.nclientv3.settings.Login.updateUser(null);
        OkHttpClient previous = Global.client;
        AtomicInteger blacklistCalls = new AtomicInteger(), galleryCalls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1), gallery = new CountDownLatch(1);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            boolean blacklist = chain.request().url().encodedPath().endsWith("/blacklist");
            int count = blacklist ? blacklistCalls.incrementAndGet() : galleryCalls.incrementAndGet();
            if (blacklist && count == 1) first.countDown();
            if (!blacklist) gallery.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(blacklist && count == 1 ? 500 : 200).message("fixture")
                .body(ResponseBody.create(blacklist ? "{\"count\":0,\"tags\":[]}"
                    : "{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(first.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                SwipeRefreshLayout refresh = controller.get().findViewById(R.id.refresher);
                while (refresh.isRefreshing() && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(refresh.isRefreshing());
                assertEquals(0, galleryCalls.get());
                android.view.View retry = controller.get().findViewById(com.google.android.material.R.id.snackbar_action);
                assertNotNull(retry);
                retry.performClick();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (gallery.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, gallery.getCount());
                assertTrue(blacklistCalls.get() >= 2);
            }
        } finally {
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void coldStartWaitsForBlacklistBeforeGalleryRequest() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "cold-start-fixture", true);
        com.maxwai.nclientv3.settings.Login.updateUser(null);
        OkHttpClient previous = Global.client;
        CountDownLatch blacklistEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch galleryEntered = new CountDownLatch(1);
        AtomicInteger galleryCalls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.endsWith("/blacklist")) {
                blacklistEntered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(exception);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("fixture").body(ResponseBody.create("{\"count\":0,\"tags\":[]}", null)).build();
            }
            galleryCalls.incrementAndGet();
            galleryEntered.countDown();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                assertTrue(blacklistEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, galleryCalls.get());
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (galleryEntered.getCount() != 0 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.yield();
                }
                assertEquals(0, galleryEntered.getCount());
            }
        } finally {
            release.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
            AuthStore.clear(app);
            com.maxwai.nclientv3.settings.Login.updateUser(null);
            app.getSharedPreferences("Settings", 0).edit()
                .remove(app.getString(R.string.preference_key_use_account_tag)).commit();
            com.maxwai.nclientv3.settings.Login.initLogin(app);
        }
    }

    @Test public void oldCompletionDoesNotStopNewRequestSpinner() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        CountDownLatch firstEntered = new CountDownLatch(1), firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1), secondRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            int number = calls.incrementAndGet();
            CountDownLatch entered = number == 1 ? firstEntered : secondEntered;
            CountDownLatch release = number == 1 ? firstRelease : secondRelease;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        java.lang.reflect.Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        boolean oldFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (org.robolectric.android.controller.ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = controller.get();
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("inspector");
                field.setAccessible(true);
                InspectorV3 first = (InspectorV3) field.get(activity);
                InspectorV3 second = first.cloneInspector(activity, first.getResponse());
                field.set(activity, second);
                java.lang.reflect.Method start = MainActivity.class.getDeclaredMethod("startInspector");
                start.setAccessible(true);
                start.invoke(activity);
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                SwipeRefreshLayout refresh = activity.findViewById(R.id.refresher);
                assertTrue(refresh.isRefreshing());
                firstRelease.countDown();
                first.join(5000);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(refresh.isRefreshing());
                secondRelease.countDown();
                second.join(5000);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(refresh.isRefreshing());
            }
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            Global.client = previous;
            firstTime.setBoolean(null, oldFirstTime);
        }
    }
}
