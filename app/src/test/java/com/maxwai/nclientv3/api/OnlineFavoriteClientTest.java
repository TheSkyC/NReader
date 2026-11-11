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
