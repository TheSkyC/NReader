package com.theskyc.nreader.api;

import android.app.Application;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.SearchPageStore;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchPageCacheFlowTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private OkHttpClient previousClient;
    private DatabaseHelper database;

    @Before public void setUp() {
        Global.initFromShared(context);
        previousClient = Global.client;
        database = new DatabaseHelper(context);
        database.getWritableDatabase();
        SearchPageStore store = SearchPageStore.open(context);
        try {
            store.clearAll();
        } finally {
            store.close();
        }
    }

    @After public void tearDown() {
        Global.client = previousClient;
        database.close();
    }

    @Test
    public void cachedPageIsDeliveredBeforeSuccessfulNetworkRefresh() throws Exception {
        String query = "cache-flow-" + UUID.randomUUID();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger requests = new AtomicInteger();
        Global.client = fixtureClient(chain -> response(chain.request(), 200,
            page(requests.incrementAndGet(), "fresh")));

        run(query, events);
        events.clear();
        run(query, events);

        assertEquals(2, requests.get());
        assertEquals(java.util.Arrays.asList("cache:1", "success:2", "end"), events);
        SearchPageStore store = SearchPageStore.open(context);
        try {
            assertEquals(1, store.stats().pageCount);
            assertEquals(2, store.cacheMetrics().refreshSuccesses);
            assertEquals(1, store.cacheMetrics().cacheHits);
        } finally {
            store.close();
        }
    }

    @Test
    public void failedNetworkRefreshLeavesCachedPageReadable() throws Exception {
        String query = "cache-failure-" + UUID.randomUUID();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        Global.client = fixtureClient(chain -> response(chain.request(), 200, page(11, "saved")));
        InspectorV3 initial = inspector(query, new InspectorV3.DefaultInspectorResponse() {});
        run(initial);

        events.clear();
        Global.client = fixtureClient(chain -> response(chain.request(), 503, "{}"));
        run(query, events);

        assertEquals(java.util.Arrays.asList("cache:11", "failure", "end"), events);
        SearchPageStore store = SearchPageStore.open(context);
        try {
            SearchPageStore.Request request = store.beginRequest(null, inspector(query,
                new InspectorV3.DefaultInspectorResponse() {}).getUrl());
            assertNotNull(store.load(request, System.currentTimeMillis()));
            store.finishRequest(request);
            assertEquals(1, store.cacheMetrics().refreshFailures);
        } finally {
            store.close();
        }
    }

    private void run(String query, List<String> events) throws InterruptedException {
        run(inspector(query, new InspectorV3.DefaultInspectorResponse() {
            @Override public void onCacheHit(List<GenericGallery> galleries, long syncedAt) {
                events.add("cache:" + galleries.get(0).getId());
            }

            @Override public void onSuccess(List<GenericGallery> galleries) {
                events.add("success:" + galleries.get(0).getId());
            }

            @Override public void onFailure(Exception error) {
                events.add("failure");
            }

            @Override public void onEnd() {
                events.add("end");
            }
        }));
    }

    private void run(InspectorV3 inspector) throws InterruptedException {
        inspector.start();
        inspector.join(10000);
        assertTrue("Inspector did not finish", !inspector.isAlive());
    }

    private InspectorV3 inspector(String query, InspectorV3.InspectorResponse response) {
        return InspectorV3.searchInspector(context, query, null, 1, SortType.RECENT_ALL_TIME, null, response);
    }

    private static OkHttpClient fixtureClient(okhttp3.Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static Response response(okhttp3.Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("Fixture").body(ResponseBody.create(body, null)).build();
    }

    private static String page(int id, String title) {
        return "{\"num_pages\":4,\"result\":[{\"id\":" + id
            + ",\"media_id\":\"fixture-" + id + "\",\"english_title\":\"" + title
            + "\",\"tag_ids\":[],\"num_pages\":1}]}";
    }
}
