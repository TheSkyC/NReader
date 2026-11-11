// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api.comments;

import android.util.JsonReader;
import android.app.Application;

import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentsFetcherTest {
    @Test
    public void parsesPaginatedCommentsAndUser() throws IOException {
        String json = "{\"num_pages\":2,\"result\":[{\"id\":17,\"post_date\":1700000000,"
            + "\"body\":\"Hello\",\"poster\":{\"id\":4,\"username\":\"reader\","
            + "\"avatar_url\":\"\"}}],\"total\":51}";
        List<Comment> comments = new ArrayList<>();
        assertEquals(2, CommentsFetcher.readPage(new JsonReader(new StringReader(json)), comments));
        assertEquals(1, comments.size());
        assertEquals("reader", comments.get(0).getUsername());
        assertEquals("Hello", comments.get(0).getComment());
        assertNull(comments.get(0).getAvatarUrl());
    }

    @Test
    public void parsesEmptyPage() throws IOException {
        List<Comment> comments = new ArrayList<>();
        assertEquals(0, CommentsFetcher.readPage(new JsonReader(new StringReader("{\"result\":[],\"num_pages\":0}")), comments));
        assertEquals(0, comments.size());
    }

    @Test
    public void rejectsMissingPageMetadataAndInvalidComments() {
        assertThrows(IOException.class, () -> CommentsFetcher.readPage(
            new JsonReader(new StringReader("{\"result\":[]}")), new ArrayList<>()));
        assertThrows(IOException.class, () -> CommentsFetcher.readPage(
            new JsonReader(new StringReader("{\"result\":[{\"id\":1}],\"num_pages\":1}")), new ArrayList<>()));
    }

    @Test
    public void usesCurrentMirrorAndRejectsInvalidRequestParameters() {
        String previous = Global.getMirror();
        try {
            ReflectionHelpers.setStaticField(Global.class, "mirror", "first.example");
            assertEquals("first.example", CommentsFetcher.createRequest(10, 1).url().host());
            ReflectionHelpers.setStaticField(Global.class, "mirror", "second.example");
            assertEquals("second.example", CommentsFetcher.createRequest(10, 2).url().host());
            assertThrows(IllegalArgumentException.class, () -> CommentsFetcher.createRequest(0, 1));
            assertThrows(IllegalArgumentException.class, () -> CommentsFetcher.createRequest(1, 0));
            assertThrows(IllegalArgumentException.class, () -> CommentsFetcher.createRequest(1, 2001));
        } finally {
            ReflectionHelpers.setStaticField(Global.class, "mirror", previous);
        }
    }

    @Test
    public void rejectsOversizedCommentPages() {
        String comment = "{\"id\":1,\"post_date\":1,\"body\":\"test\",\"poster\":{\"id\":1}}";
        StringBuilder json = new StringBuilder("{\"num_pages\":1,\"result\":[");
        for (int index = 0; index < 51; index++) {
            if (index > 0) json.append(',');
            json.append(comment);
        }
        json.append("]}");
        assertThrows(IOException.class, () -> CommentsFetcher.readPage(
            new JsonReader(new StringReader(json.toString())), new ArrayList<>()));
    }

    @Test
    public void malformedResponseDoesNotReturnPartialComments() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        String body = "{\"num_pages\":1,\"result\":[{\"id\":1,\"post_date\":1,\"body\":\"ok\","
            + "\"poster\":{\"id\":1}},{\"id\":2}]}";
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(ResponseBody.create(body, null)).build()).build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);
        AtomicReference<List<Comment>> returned = new AtomicReference<>();
        CommentsFetcher.fetch(context, 1, 1, client, (comments, pages, loaded) -> {
            returned.set(comments);
            success.set(loaded);
            completed.countDown();
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertFalse(success.get());
        assertTrue(returned.get().isEmpty());
    }

    @Test
    public void unsuccessfulAndOversizedResponsesFailWithoutResults() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        String oversized = new String(new char[2 * 1024 * 1024 + 1]).replace('\0', 'x');
        for (int status : new int[]{429, 200}) {
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("Synthetic response").body(ResponseBody.create(status == 429 ? "{}" : oversized, null))
                    .build()).build();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(true);
            AtomicReference<List<Comment>> returned = new AtomicReference<>();
            CommentsFetcher.fetch(context, 1, 1, client, (comments, pages, loaded) -> {
                returned.set(comments);
                success.set(loaded);
                completed.countDown();
            });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertFalse(success.get());
            assertTrue(returned.get().isEmpty());
        }
    }

    @Test
    public void cancelStopsActiveCallAndSuppressesItsResult() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<Call> active = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            active.set(chain.call());
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(ResponseBody.create("{\"num_pages\":0,\"result\":[]}", null)).build();
        }).build();
        CommentsFetcher.RequestHandle handle = CommentsFetcher.fetch(context, 1, 1, client,
            (comments, pages, success) -> callback.countDown());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            handle.cancel();
            assertTrue(active.get().isCanceled());
        } finally {
            release.countDown();
        }
        assertFalse(callback.await(500, TimeUnit.MILLISECONDS));
    }
}
