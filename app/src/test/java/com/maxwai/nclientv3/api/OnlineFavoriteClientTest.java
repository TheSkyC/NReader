// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api;

import android.app.Application;

import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Before;
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

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineFavoriteClientTest {
    private Application context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
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
            assertTrue(list.createDocument());
            list.parseDocument();
            assertNull(OnlineFavoriteClient.cachedState(context, 403));
        } finally {
            Global.client = previous;
        }
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
}
