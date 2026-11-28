// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.api;

import android.app.Application;

import com.theskyc.nreader.settings.ApiAuthInterceptor;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.AuthSessionStore;
import com.theskyc.nreader.R;
import com.theskyc.nreader.async.database.OnlineFavoritePageStore;
import com.theskyc.nreader.settings.AccountRuntimeTestScope;
import com.theskyc.nreader.settings.Global;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineFavoriteClientTest {
    @Rule public final AccountRuntimeTestScope accountRuntimeScope = new AccountRuntimeTestScope();
    private Application context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        context.getSharedPreferences("Settings", 0).edit()
            .putString(context.getString(R.string.preference_key_online_favorite_state_limit), "512").commit();
        AuthStore.clear(context);
        OnlineFavoriteClient.sessionIdentity(context);
        AuthStore.saveApiKey(context, "synthetic-key", true);
    }

    private OkHttpClient client(Interceptor fakeServer) {
        return new OkHttpClient.Builder().addInterceptor(new ApiAuthInterceptor(context))
            .addInterceptor(fakeServer).build();
    }

    private static Response response(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Synthetic response").body(ResponseBody.create(body, null)).build();
    }

    private static final class Outcome implements OnlineFavoriteClient.Result {
        final CountDownLatch done = new CountDownLatch(1);
        Boolean value;
        boolean failed;
        @Override public void onSuccess(boolean favorited) { value = favorited; done.countDown(); }
        @Override public void onFailure() { failed = true; done.countDown(); }
        void await() throws InterruptedException { assertTrue(done.await(5, TimeUnit.SECONDS)); }
    }

    @Test public void sessionFavoritesUseOfficialHostWhenMirrorIsConfigured() throws Exception {
        context.getSharedPreferences("Settings", 0).edit()
            .putString(context.getString(R.string.preference_key_site_mirror), "mirror.example").commit();
        Global.initFromShared(context);
        assertTrue(AuthSessionStore.replace(context,
            AuthSessionStore.tokenCookies("fixture-access", "fixture-refresh"), null));
        OkHttpClient previous = Global.client;
        try {
            OkHttpClient fake = client(chain -> {
                assertEquals("nhentai.net", chain.request().url().host());
                assertEquals("User fixture-access", chain.request().header("Authorization"));
                String path = chain.request().url().encodedPath();
                return response(chain, 200, path.endsWith("/favorites")
                    ? "{\"num_pages\":1,\"result\":[]}"
                    : "{\"favorited\":" + (!"GET".equals(chain.request().method())) + "}");
            });
            Outcome outcome = new Outcome();
            OnlineFavoriteClient.toggle(context, 512, fake, outcome);
            outcome.await();
            assertFalse(outcome.failed);
            Global.client = fake;
            InspectorV3 favorites = InspectorV3.favoriteInspector(context, null, 1, null);
            assertTrue(favorites.createDocument());
            favorites.parseDocument();
        } finally {
            Global.client = previous;
            AuthSessionStore.clear(context);
            context.getSharedPreferences("Settings", 0).edit()
                .remove(context.getString(R.string.preference_key_site_mirror)).commit();
            Global.initFromShared(context);
        }
    }

    @Test public void unknownStateIsFetchedBeforeMutationAndCached() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        OkHttpClient client = client(chain -> {
            methods.add(chain.request().method());
            return response(chain, 200, methods.size() == 1 ? "{\"favorited\":false}" : "{\"favorited\":true}");
        });
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 201, client, outcome);
        outcome.await();
        assertEquals(java.util.Arrays.asList("GET", "POST"), methods);
        assertEquals(Boolean.TRUE, outcome.value);
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 201));
    }

    @Test public void restoredStateIsAccountScopedAndRecheckedBeforeToggle() throws Exception {
        OnlineFavoriteClient.sessionIdentity(context);
        OnlineFavoriteClient.rememberState(context, 99001, true);
        awaitStorage();
        AuthStore.saveApiKey(context, "another-cache-account", true);
        assertNull(OnlineFavoriteClient.cachedState(context, 99001));
        AuthStore.saveApiKey(context, "synthetic-key", true);
        OnlineFavoriteClient.sessionIdentity(context);
        awaitStorage();
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 99001));
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 99001, client(chain -> {
            methods.add(chain.request().method());
            return response(chain, 200, methods.size() == 1
                ? "{\"favorited\":false}" : "{\"favorited\":true}");
        }), outcome);
        outcome.await();
        assertEquals(java.util.Arrays.asList("GET", "POST"), methods);
        assertEquals(Boolean.TRUE, outcome.value);
    }

    @Test public void queuedPersistenceDoesNotEraseStateAfterSwitchingAwayAndBack() throws Exception {
        int existingGalleryId = 99002;
        int queuedGalleryId = 99003;
        String identity = OnlineFavoriteClient.sessionIdentity(context);
        OnlineFavoriteClient.rememberState(context, existingGalleryId, true);
        awaitStorage();

        java.lang.reflect.Field field = OnlineFavoriteClient.class.getDeclaredField("storage");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService storage = (java.util.concurrent.ExecutorService) field.get(null);
        CountDownLatch storageBlocked = new CountDownLatch(1);
        CountDownLatch releaseStorage = new CountDownLatch(1);
        storage.submit(() -> {
            storageBlocked.countDown();
            try {
                releaseStorage.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(storageBlocked.await(5, TimeUnit.SECONDS));

        try {
            OnlineFavoriteClient.rememberState(context, queuedGalleryId, true);
            AuthStore.saveApiKey(context, "temporary-cache-account", true);
            OnlineFavoriteClient.sessionIdentity(context);
            AuthStore.saveApiKey(context, "synthetic-key", true);
            OnlineFavoriteClient.sessionIdentity(context);
        } finally {
            releaseStorage.countDown();
        }
        awaitStorage();

        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, existingGalleryId));
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, queuedGalleryId));
        org.json.JSONObject persisted = new org.json.JSONObject(context
            .getSharedPreferences("online_favorite_cache", 0).getString("states", "{}"));
        assertEquals(identity, context.getSharedPreferences("online_favorite_cache", 0)
            .getString("identity", null));
        assertTrue(persisted.getBoolean(String.valueOf(existingGalleryId)));
        assertTrue(persisted.getBoolean(String.valueOf(queuedGalleryId)));
    }

    private void awaitStorage() throws Exception {
        java.lang.reflect.Field field = OnlineFavoriteClient.class.getDeclaredField("storage");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test public void favoriteListSnapshotRespectsAccountAndSubsequentChanges() {
        OnlineFavoriteClient.FavoriteListSnapshot first = OnlineFavoriteClient.beginFavoriteList(context);
        OnlineFavoriteClient.acceptFavoriteList(context, first, java.util.Arrays.asList(301, 302));
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 301));
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 302));
        assertNull(OnlineFavoriteClient.cachedState(context, 303));

        OnlineFavoriteClient.FavoriteListSnapshot stale = OnlineFavoriteClient.beginFavoriteList(context);
        OnlineFavoriteClient.rememberState(context, 301, false);
        OnlineFavoriteClient.acceptFavoriteList(context, stale, Collections.singletonList(301));
        assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(context, 301));

        OnlineFavoriteClient.FavoriteListSnapshot oldAccount = OnlineFavoriteClient.beginFavoriteList(context);
        AuthStore.saveApiKey(context, "other-account", true);
        OnlineFavoriteClient.acceptFavoriteList(context, oldAccount, Collections.singletonList(304));
        assertNull(OnlineFavoriteClient.cachedState(context, 304));
        AuthStore.saveApiKey(context, "synthetic-key", true);
        OnlineFavoriteClient.sessionIdentity(context);
        OnlineFavoriteClient.acceptFavoriteList(context, oldAccount, Collections.singletonList(304));
        assertNull(OnlineFavoriteClient.cachedState(context, 304));
    }

    @Test public void favoriteListDoesNotOverwriteAnInFlightMutation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OnlineFavoriteClient.rememberState(context, 305, true);
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 305, client(chain -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return response(chain, 200, "{\"favorited\":false}");
        }), outcome);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OnlineFavoriteClient.FavoriteListSnapshot snapshot = OnlineFavoriteClient.beginFavoriteList(context);
            OnlineFavoriteClient.acceptFavoriteList(context, snapshot, Collections.singletonList(306));
            assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 306));
            release.countDown();
            outcome.await();
            OnlineFavoriteClient.acceptFavoriteList(context, snapshot, Collections.singletonList(305));
            assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(context, 305));
        } finally {
            release.countDown();
        }
    }

    @Test public void favoriteListKeepsUnaffectedItemsAfterAnOverlappingChange() {
        OnlineFavoriteClient.FavoriteListSnapshot snapshot = OnlineFavoriteClient.beginFavoriteList(context);
        OnlineFavoriteClient.rememberState(context, 307, false);
        OnlineFavoriteClient.acceptFavoriteList(context, snapshot, java.util.Arrays.asList(307, 308));
        assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(context, 307));
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 308));
    }

    @Test public void oldFavoriteListIsRejectedWhenChangeHistoryIsEvicted() {
        OnlineFavoriteClient.FavoriteListSnapshot snapshot = OnlineFavoriteClient.beginFavoriteList(context);
        for (int id = 10000; id < 10513; id++) OnlineFavoriteClient.rememberState(context, id, false);
        OnlineFavoriteClient.acceptFavoriteList(context, snapshot, java.util.Arrays.asList(10000, 20000));
        assertNull(OnlineFavoriteClient.cachedState(context, 10000));
        assertNull(OnlineFavoriteClient.cachedState(context, 20000));
    }

    @Test public void configuredCapacityExpandsCacheAndTrimsItWhenReduced() throws Exception {
        context.getSharedPreferences("Settings", 0).edit()
            .putString(context.getString(R.string.preference_key_online_favorite_state_limit), "2048").commit();
        for (int id = 70000; id < 70600; id++) OnlineFavoriteClient.rememberState(context, id, true);
        awaitStorage();

        assertTrue(OnlineFavoriteClient.cachedStateCount(context) >= 600);

        OnlineFavoriteClient.applyCacheCapacity(context, "512");
        assertEquals("2048", context.getSharedPreferences("Settings", 0).getString(
            context.getString(R.string.preference_key_online_favorite_state_limit), null));
        assertEquals(512, OnlineFavoriteClient.cachedStateCount(context));

        context.getSharedPreferences("Settings", 0).edit()
            .putString(context.getString(R.string.preference_key_online_favorite_state_limit), "512").commit();
        awaitStorage();
        org.json.JSONObject persisted = new org.json.JSONObject(context
            .getSharedPreferences("online_favorite_cache", 0).getString("states", "{}"));
        assertEquals(512, persisted.length());
    }

    @Test public void authenticatedFavoriteListSeedsOnlySuccessfulCurrentAccountResults() throws Exception {
        OkHttpClient previous = Global.client;
        try {
            Global.client = client(chain -> response(chain, 200,
                "{\"num_pages\":1,\"result\":[{\"id\":401,\"media_id\":\"1\",\"num_pages\":1}]}"));
            InspectorV3 list = InspectorV3.favoriteInspector(context, null, 1, null);
            assertTrue(list.createDocument());
            list.parseDocument();
            assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 401));

            Global.client = client(chain -> response(chain, 500,
                "{\"num_pages\":1,\"result\":[{\"id\":402,\"media_id\":\"1\",\"num_pages\":1}]}"));
            list = InspectorV3.favoriteInspector(context, null, 1, null);
            assertFalse(list.createDocument());
            list.parseDocument();
            assertNull(OnlineFavoriteClient.cachedState(context, 402));

            Global.client = client(chain -> {
                AuthStore.saveApiKey(context, "new-list-account", true);
                return response(chain, 200,
                    "{\"num_pages\":1,\"result\":[{\"id\":403,\"media_id\":\"1\",\"num_pages\":1}]}");
            });
            list = InspectorV3.favoriteInspector(context, null, 1, null);
            try {
                list.createDocument();
                org.junit.Assert.fail("A response from a previous account must be rejected");
            } catch (IOException expected) {
                assertEquals("API operation expired", expected.getMessage());
            }
            assertNull(OnlineFavoriteClient.cachedState(context, 403));

            AuthStore.saveApiKey(context, "first-list-account", true);
            Global.client = client(chain -> {
                AuthStore.saveApiKey(context, "other-list-account", true);
                AuthStore.saveApiKey(context, "first-list-account", true);
                return response(chain, 200, "{\"num_pages\":1,\"result\":[]}");
            });
            list = InspectorV3.favoriteInspector(context, null, 1, null);
            try {
                list.createDocument();
                org.junit.Assert.fail("An earlier generation of the same identity must be rejected");
            } catch (IOException expected) {
                assertEquals("API operation expired", expected.getMessage());
                assertFalse(list.isFavoriteIdentityCurrent());
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test public void cachedFavoritePageIsDeliveredBeforeOfflineFailureWithoutChangingState() throws Exception {
        String identity = OnlineFavoriteClient.sessionIdentity(context);
        OnlineFavoritePageStore pageStore = OnlineFavoritePageStore.open(context);
        pageStore.clearAccount(identity);
        pageStore.close();

        OkHttpClient previous = Global.client;
        try {
            Global.client = client(chain -> response(chain, 200,
                "{\"num_pages\":2,\"result\":[{\"id\":401,\"media_id\":\"1\","
                    + "\"thumbnail\":\"g/1.jpg\",\"english_title\":\"Saved page\",\"num_pages\":1}]}"));
            List<String> firstEvents = Collections.synchronizedList(new ArrayList<>());
            runFavoriteInspector(firstEvents, new AtomicInteger(-1));
            assertEquals(Collections.singletonList("fresh"), firstEvents);
            pageStore = OnlineFavoritePageStore.open(context);
            OnlineFavoritePageStore.CacheMetrics beforeOfflineRefresh = pageStore.cacheMetrics();
            pageStore.close();

            OnlineFavoriteClient.rememberState(context, 401, false);
            Global.client = client(chain -> { throw new IOException("synthetic offline failure"); });
            List<String> offlineEvents = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger cachedGalleryId = new AtomicInteger(-1);
            runFavoriteInspector(offlineEvents, cachedGalleryId);

            assertEquals(java.util.Arrays.asList("cache", "failure"), offlineEvents);
            assertEquals(401, cachedGalleryId.get());
            assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(context, 401));
            pageStore = OnlineFavoritePageStore.open(context);
            OnlineFavoritePageStore.CacheMetrics afterOfflineRefresh = pageStore.cacheMetrics();
            assertEquals(beforeOfflineRefresh.cacheHits + 1, afterOfflineRefresh.cacheHits);
            assertEquals(beforeOfflineRefresh.refreshFailures + 1, afterOfflineRefresh.refreshFailures);
            pageStore.close();
        } finally {
            Global.client = previous;
        }
    }

    @Test public void cachedFavoritePageRefreshesFromNetworkAndReplacesStoredPage() throws Exception {
        String identity = OnlineFavoriteClient.sessionIdentity(context);
        String url = "https://nhentai.net/api/v2/favorites?page=1";
        OnlineFavoritePageStore pageStore = OnlineFavoritePageStore.open(context);
        pageStore.clearAccount(identity);
        OnlineFavoritePageStore.Request write = pageStore.beginRequest(identity, url);
        assertTrue(pageStore.save(write,
            "{\"num_pages\":2,\"result\":[{\"id\":410,\"media_id\":\"1\","
                + "\"english_title\":\"Cached\",\"num_pages\":1}]}", 2, 100));
        pageStore.finishRequest(write);
        OnlineFavoritePageStore.CacheMetrics beforeRefresh = pageStore.cacheMetrics();
        pageStore.close();
        OnlineFavoriteClient.rememberState(context, 410, false);

        OkHttpClient previous = Global.client;
        try {
            AtomicInteger networkCalls = new AtomicInteger();
            Global.client = client(chain -> {
                networkCalls.incrementAndGet();
                return response(chain, 200,
                    "{\"num_pages\":3,\"result\":[{\"id\":411,\"media_id\":\"2\","
                        + "\"english_title\":\"Fresh\",\"num_pages\":1}]} ");
            });

            List<String> events = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger cachedGalleryId = new AtomicInteger(-1);
            AtomicInteger freshGalleryId = new AtomicInteger(-1);
            runFavoriteInspector(events, cachedGalleryId, freshGalleryId);

            assertEquals(java.util.Arrays.asList("cache", "fresh"), events);
            assertEquals(1, networkCalls.get());
            assertEquals(410, cachedGalleryId.get());
            assertEquals(411, freshGalleryId.get());
            assertEquals(Boolean.FALSE, OnlineFavoriteClient.cachedState(context, 410));

            pageStore = OnlineFavoritePageStore.open(context);
            OnlineFavoritePageStore.Request read = pageStore.beginRequest(identity, url);
            OnlineFavoritePageStore.CachedPage cached = pageStore.load(read, System.currentTimeMillis());
            assertNotNull(cached);
            assertTrue(cached.syncedAt > 100);
            assertEquals(3, cached.pageCount);
            assertEquals(411, new org.json.JSONObject(cached.payload)
                .getJSONArray("result").getJSONObject(0).getInt("id"));
            OnlineFavoritePageStore.CacheMetrics afterRefresh = pageStore.cacheMetrics();
            assertEquals(beforeRefresh.cacheHits + 1, afterRefresh.cacheHits);
            assertEquals(beforeRefresh.refreshSuccesses + 1, afterRefresh.refreshSuccesses);
            pageStore.finishRequest(read);
            pageStore.close();
        } finally {
            Global.client = previous;
        }
    }

    @Test public void successfulFavoriteMutationInvalidatesThatAccountsCachedPages() throws Exception {
        AuthStore.saveApiKey(context, "favorite-page-test-key", true);
        String identity = OnlineFavoriteClient.sessionIdentity(context);
        String url = "https://nhentai.net/api/v2/favorites?page=1";
        OnlineFavoritePageStore pageStore = OnlineFavoritePageStore.open(context);
        pageStore.clearAccount(identity);
        OnlineFavoritePageStore.Request write = pageStore.beginRequest(identity, url);
        assertTrue(pageStore.save(write,
            "{\"result\":[{\"id\":402,\"media_id\":\"1\",\"english_title\":\"Saved\"}]}", 1, 100));
        pageStore.finishRequest(write);
        pageStore.close();
        OnlineFavoriteClient.rememberState(context, 402, true);

        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 402, client(chain -> {
            assertEquals("DELETE", chain.request().method());
            return response(chain, 200, "{\"favorited\":false}");
        }), outcome);
        outcome.await();
        assertEquals(Boolean.FALSE, outcome.value);

        pageStore = OnlineFavoritePageStore.open(context);
        awaitPageCount(pageStore, identity, 0);
        OnlineFavoritePageStore.Request read = pageStore.beginRequest(identity, url);
        assertNull(pageStore.load(read, 200));
        pageStore.finishRequest(read);
        pageStore.close();
    }

    private static void awaitPageCount(OnlineFavoritePageStore store, String identity, int expected)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < deadline && store.stats(identity).pageCount != expected) {
            Thread.sleep(10);
        }
        assertEquals(expected, store.stats(identity).pageCount);
    }

    private void runFavoriteInspector(List<String> events, AtomicInteger cachedGalleryId) throws Exception {
        runFavoriteInspector(events, cachedGalleryId, new AtomicInteger(-1));
    }

    private void runFavoriteInspector(List<String> events, AtomicInteger cachedGalleryId,
                                      AtomicInteger freshGalleryId) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        InspectorV3 inspector = InspectorV3.favoriteInspector(context, null, 1,
            new InspectorV3.DefaultInspectorResponse() {
                @Override public void onCacheHit(List<com.theskyc.nreader.api.components.GenericGallery> galleries,
                                                 long syncedAt) {
                    events.add("cache");
                    cachedGalleryId.set(galleries.get(0).getId());
                }
                @Override public void onSuccess(List<com.theskyc.nreader.api.components.GenericGallery> galleries) {
                    events.add("fresh");
                    freshGalleryId.set(galleries.get(0).getId());
                }
                @Override public void onFailure(Exception error) {
                    events.add("failure");
                }
                @Override public void onEnd() { ended.countDown(); }
            });
        inspector.start();
        assertTrue(ended.await(5, TimeUnit.SECONDS));
    }

    @Test public void rejectedWriteKeepsConfirmedState() throws Exception {
        OnlineFavoriteClient.rememberState(context, 202, true);
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 202, client(chain -> {
            methods.add(chain.request().method());
            return response(chain, 429, "");
        }), outcome);
        outcome.await();
        assertTrue(outcome.failed);
        assertEquals(Collections.singletonList("DELETE"), methods);
        assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, 202));
    }

    @Test public void failedWriteInvalidatesPreviouslyConfirmedState() throws Exception {
        OnlineFavoriteClient.rememberState(context, 203, true);
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 203, client(chain -> { throw new IOException("synthetic timeout"); }), outcome);
        outcome.await();
        assertTrue(outcome.failed);
        assertNull(OnlineFavoriteClient.cachedState(context, 203));
    }

    @Test public void contradictoryWriteResultInvalidatesState() throws Exception {
        int galleryId = 8372;
        OnlineFavoriteClient.rememberState(context, galleryId, true);
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, galleryId,
            client(chain -> response(chain, 200, "{\"favorited\":true}")), outcome);
        outcome.await();
        assertTrue(outcome.failed);
        assertNull(OnlineFavoriteClient.cachedState(context, galleryId));
    }

    @Test public void ambiguousWriteResponsesInvalidatePreviouslyConfirmedState() throws Exception {
        int[] statuses = {302, 408, 503};
        for (int status : statuses) {
            int galleryId = 3000 + status;
            OnlineFavoriteClient.rememberState(context, galleryId, true);
            List<String> methods = Collections.synchronizedList(new ArrayList<>());
            Outcome outcome = new Outcome();
            OnlineFavoriteClient.toggle(context, galleryId, client(chain -> {
                methods.add(chain.request().method());
                return response(chain, status, "");
            }), outcome);
            outcome.await();
            assertTrue(outcome.failed);
            assertEquals(Collections.singletonList("DELETE"), methods);
            assertNull(OnlineFavoriteClient.cachedState(context, galleryId));
        }
    }

    @Test public void oversizedWriteResponseLeavesStateUnknown() throws Exception {
        int galleryId = 8123;
        OnlineFavoriteClient.rememberState(context, galleryId, true);
        char[] payload = new char[65537];
        java.util.Arrays.fill(payload, ' ');
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, galleryId,
            client(chain -> response(chain, 200, new String(payload))), outcome);
        outcome.await();
        assertTrue(outcome.failed);
        assertNull(OnlineFavoriteClient.cachedState(context, galleryId));
    }

    @Test public void writeDoesNotFollowRedirect() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(3000);
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            Thread responder = new Thread(() -> {
                try {
                    while (requests.get() < 2) {
                        try (java.net.Socket socket = server.accept()) {
                            requests.incrementAndGet();
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                            String reply = "HTTP/1.1 307 Temporary Redirect\r\nLocation: /redirected\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(reply.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (IOException ignored) {
                    // The accept timeout ends the test after the first response.
                }
            });
            responder.start();
            OkHttpClient local = new OkHttpClient.Builder().addInterceptor(chain -> {
                okhttp3.HttpUrl localUrl = chain.request().url().newBuilder()
                    .scheme("http").host("127.0.0.1").port(server.getLocalPort()).build();
                return chain.proceed(chain.request().newBuilder().url(localUrl).build());
            }).build();
            OnlineFavoriteClient.rememberState(context, 8124, true);
            Outcome outcome = new Outcome();
            OnlineFavoriteClient.toggle(context, 8124, local, outcome);
            outcome.await();
            responder.join(5000);
            assertFalse(responder.isAlive());
            assertTrue(outcome.failed);
            assertEquals(1, requests.get());
            assertNull(OnlineFavoriteClient.cachedState(context, 8124));
        }
    }

    @Test public void accountChangeAfterLookupPreventsMutation() throws Exception {
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        Outcome outcome = new Outcome();
        OnlineFavoriteClient.toggle(context, 204, client(chain -> {
            methods.add(chain.request().method());
            AuthStore.saveApiKey(context, "other-synthetic-key", true);
            return response(chain, 200, "{\"favorited\":false}");
        }), outcome);
        outcome.await();
        assertTrue(outcome.failed);
        assertEquals(Collections.singletonList("GET"), methods);
        assertNull(OnlineFavoriteClient.cachedState(context, 204));
    }

    @Test public void duplicateInFlightToggleDoesNotSendAnotherRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        OnlineFavoriteClient.rememberState(context, 205, false);
        OkHttpClient client = client(chain -> {
            methods.add(chain.request().method());
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return response(chain, 200, "{\"favorited\":true}");
        });
        Outcome first = new Outcome();
        Outcome duplicate = new Outcome();
        try {
            OnlineFavoriteClient.toggle(context, 205, client, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OnlineFavoriteClient.toggle(context, 205, client, duplicate);
            duplicate.await();
            assertTrue(duplicate.failed);
            assertFalse(first.failed);
        } finally {
            release.countDown();
        }
        first.await();
        assertEquals(Collections.singletonList("POST"), methods);
        assertEquals(Boolean.TRUE, first.value);
    }

    @Test public void staleFailureDoesNotClearStateAfterSwitchingAwayAndBack() throws Exception {
        int galleryId = 9135;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OnlineFavoriteClient.rememberState(context, galleryId, false);
        Outcome outcome = new Outcome();
        try {
            OnlineFavoriteClient.toggle(context, galleryId, client(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                throw new IOException("Synthetic uncertain result");
            }), outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AuthStore.saveApiKey(context, "different-fixture-key", true);
            OnlineFavoriteClient.sessionIdentity(context);
            AuthStore.saveApiKey(context, "synthetic-key", true);
            OnlineFavoriteClient.sessionIdentity(context);
            OnlineFavoriteClient.rememberState(context, galleryId, true);
            release.countDown();
            outcome.await();
            assertTrue(outcome.failed);
            assertEquals(Boolean.TRUE, OnlineFavoriteClient.cachedState(context, galleryId));
        } finally {
            release.countDown();
        }
    }

    @Test public void queuedMutationIsRejectedAfterSwitchingAwayAndBack() throws Exception {
        int galleryId = 9136;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger dispatched = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpClient delayed = new OkHttpClient.Builder().addInterceptor(chain -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return chain.proceed(chain.request());
        }).addInterceptor(new ApiAuthInterceptor(context)).addInterceptor(chain -> {
            dispatched.incrementAndGet();
            return response(chain, 200, "{\"favorited\":true}");
        }).build();
        OnlineFavoriteClient.rememberState(context, galleryId, false);
        Outcome outcome = new Outcome();
        try {
            OnlineFavoriteClient.toggle(context, galleryId, delayed, outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread switcher = new Thread(() -> {
                AuthStore.saveApiKey(context, "different-fixture-key", true);
                AuthStore.saveApiKey(context, "synthetic-key", true);
            });
            switcher.start();
            switcher.join(5000);
            assertFalse(switcher.isAlive());
            release.countDown();
            outcome.await();
            assertTrue(outcome.failed);
            assertEquals(0, dispatched.get());
        } finally {
            release.countDown();
        }
    }
}
