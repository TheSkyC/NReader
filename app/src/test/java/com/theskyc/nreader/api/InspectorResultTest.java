package com.theskyc.nreader.api;

import android.app.Application;

import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class InspectorResultTest {
    @Test public void exactMatchingLeavesCustomTermsToTheSite() throws Exception {
        Tag custom = new Tag("custom", 0, SpecialTagIds.INVALID_ID, TagType.TAG, TagStatus.DEFAULT);
        Tag included = new Tag("included", 0, 900001, TagType.TAG, TagStatus.DEFAULT);
        InspectorV3 inspector = searchFixture(Arrays.asList(custom, included), "fixture title",
            Arrays.asList("fixture title", "tag:\"custom\"", "tag:\"included\""),
            "{\"id\":1,\"media_id\":\"21\",\"tag_ids\":[900001]}", true);

        assertEquals(Collections.singletonList(1), resultIds(inspector));
    }

    @Test public void exactMatchingUsesReturnedIdsForIncludedAndExcludedTags() throws Exception {
        Tag included = new Tag("included", 0, 900001, TagType.TAG, TagStatus.ACCEPTED);
        Tag excluded = new Tag("excluded", 0, 900002, TagType.TAG, TagStatus.AVOIDED);
        List<Tag> queryTags = Arrays.asList(included, excluded);
        String rows = "{\"id\":1,\"media_id\":\"21\",\"tag_ids\":[900001,900003]},"
            + "{\"id\":2,\"media_id\":\"22\",\"tag_ids\":[900001,900002]},"
            + "{\"id\":3,\"media_id\":\"23\",\"tag_ids\":[900003]}";
        List<String> expectedTerms = Arrays.asList("tag:\"included\"", "-tag:\"excluded\"");
        InspectorV3 exact = searchFixture(queryTags, null, expectedTerms, rows, true);
        InspectorV3 regular = searchFixture(queryTags, null, expectedTerms, rows, false);

        assertEquals(Collections.singletonList(1), resultIds(exact));
        assertEquals(Arrays.asList(1, 2, 3), resultIds(regular));
        assertEquals(4, exact.getPageCount());
    }

    @Test public void identicalSiteTermsAreSentOnceAndCheckedByBothIds() throws Exception {
        Tag first = new Tag("name+suffix", 0, 900001, TagType.TAG, TagStatus.ACCEPTED);
        Tag second = new Tag("name+suffix", 0, 900002, TagType.TAG, TagStatus.ACCEPTED);
        String queryTerm = "tag:\"name+suffix\"";
        String rows = "{\"id\":1,\"media_id\":\"21\",\"tag_ids\":[900001,900002]},"
            + "{\"id\":2,\"media_id\":\"22\",\"tag_ids\":[900001]}";
        InspectorV3 inspector = searchFixture(Arrays.asList(first, second), null,
            Collections.singletonList(queryTerm), rows, true);

        assertEquals(queryTerm, HttpUrl.get(inspector.getUrl()).queryParameter("query").trim());
        assertEquals(Collections.singletonList(1), resultIds(inspector));
    }

    @Test public void oppositeTermsWithTheSameNameRemainDistinct() throws Exception {
        Tag included = new Tag("shared", 0, 900001, TagType.TAG, TagStatus.ACCEPTED);
        Tag excluded = new Tag("shared", 0, 900002, TagType.TAG, TagStatus.AVOIDED);
        String positive = included.toQueryTag();
        String negative = excluded.toQueryTag();
        InspectorV3 inspector = searchFixture(Arrays.asList(included, excluded), null,
            Arrays.asList(positive, negative), "", false);

        String query = HttpUrl.get(inspector.getUrl()).queryParameter("query").trim();
        assertTrue(query.equals(positive + " " + negative) || query.equals(negative + " " + positive));
    }

    private static InspectorV3 searchFixture(List<Tag> tags, String freeText,
                                             List<String> queryTerms, String rows,
                                             boolean exact) throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String key = app.getString(R.string.preference_key_exact_title_match);
        app.getSharedPreferences("Settings", 0).edit().putBoolean(key, exact).commit();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        AtomicReference<String> query = new AtomicReference<>();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            query.set(chain.request().url().queryParameter("query"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":4,\"result\":[" + rows + "]}", null)).build();
        }).build();
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            InspectorV3 inspector = InspectorV3.searchInspector(app, freeText, tags, 1,
                SortType.RECENT_ALL_TIME, null, new InspectorV3.DefaultInspectorResponse() {});
            assertTrue(inspector.createDocument());
            inspector.parseDocument();
            for (String term : queryTerms) assertTrue(query.get().contains(term));
            return inspector;
        } finally {
            Global.client = previous;
            app.getSharedPreferences("Settings", 0).edit().remove(key).commit();
            Global.initFromShared(app);
        }
    }

    private static List<Integer> resultIds(InspectorV3 inspector) {
        List<Integer> ids = new ArrayList<>();
        for (GenericGallery gallery : inspector.getGalleries()) ids.add(gallery.getId());
        return ids;
    }

    @Test public void unsuccessfulResponseCannotPublishResults() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        AtomicInteger published = new AtomicInteger(), failed = new AtomicInteger();
        OkHttpClient previous = Global.client;
        Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build()).build();
        try {
            InspectorV3 inspector = InspectorV3.savedSearchInspector(app, "fixture", 1, null,
                new InspectorV3.DefaultInspectorResponse() {
                    @Override public void onSuccess(List<GenericGallery> galleries) { published.incrementAndGet(); }
                    @Override public void onFailure(Exception exception) { failed.incrementAndGet(); }
                });
            inspector.start();
            inspector.join(5000);
            assertTrue(!inspector.isAlive());
            assertEquals(0, published.get());
            assertEquals(1, failed.get());
            assertEquals(503, inspector.getResponseCode());
        } finally {
            Global.client = previous;
        }
    }

    @Test public void staleResponseDoesNotPublishButStillEnds() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger published = new AtomicInteger(), ended = new AtomicInteger();
        OkHttpClient previous = Global.client;
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
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
        try {
            InspectorV3 inspector = InspectorV3.savedSearchInspector(app, "fixture", 1, null,
                new InspectorV3.DefaultInspectorResponse() {
                    @Override public void onSuccess(List<GenericGallery> galleries) { published.incrementAndGet(); }
                    @Override public void onFailure(Exception exception) { published.incrementAndGet(); }
                    @Override public void onEnd() { ended.incrementAndGet(); }
                });
            inspector.setResultCurrent(current::get);
            inspector.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            current.set(false);
            release.countDown();
            inspector.join(5000);
            assertTrue(!inspector.isAlive());
            assertEquals(0, published.get());
            assertEquals(1, ended.get());
        } finally {
            release.countDown();
            Global.client = previous;
        }
    }

    @Test public void clonedInspectorDoesNotInheritOldRequestPredicate() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        AtomicInteger published = new AtomicInteger();
        OkHttpClient previous = Global.client;
        Global.client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build()).build();
        try {
            InspectorV3 original = InspectorV3.savedSearchInspector(app, "fixture", 1, null,
                new InspectorV3.DefaultInspectorResponse() {});
            original.setResultCurrent(() -> false);
            InspectorV3 clone = original.cloneInspector(app, new InspectorV3.DefaultInspectorResponse() {
                @Override public void onSuccess(List<GenericGallery> galleries) { published.incrementAndGet(); }
            });
            clone.start();
            clone.join(5000);
            assertTrue(!clone.isAlive());
            assertEquals(1, published.get());
        } finally {
            Global.client = previous;
        }
    }

    @Test public void cancellationAlsoCancelsRelatedTagRequest() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Call> call = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            call.set(chain.call());
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create("[]", null)).build();
        }).build();
        try {
            InspectorV3 inspector = InspectorV3.galleryInspector(app, 42, new InspectorV3.DefaultInspectorResponse() {});
            java.lang.reflect.Method fetch = InspectorV3.class.getDeclaredMethod("fetchTagBatch", List.class, List.class);
            fetch.setAccessible(true);
            Thread worker = new Thread(() -> {
                try {
                    fetch.invoke(inspector, Collections.singletonList(1), new ArrayList<>());
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            inspector.cancelRequest();
            assertTrue(call.get().isCanceled());
            release.countDown();
            worker.join(5000);
            assertTrue(!worker.isAlive());
        } finally {
            release.countDown();
            Global.client = previous;
        }
    }
}
