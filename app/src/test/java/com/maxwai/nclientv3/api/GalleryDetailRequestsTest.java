// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.AuthStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryDetailRequestsTest {
    private static final String DETAIL = "{\"id\":117,\"media_id\":1,\"num_pages\":1,"
        + "\"title\":{\"pretty\":\"Test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
        + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
        + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}],"
        + "\"related\":[{\"id\":118,\"media_id\":\"2\",\"thumbnail\":\"galleries/2/thumb.jpg\","
        + "\"english_title\":\"Related\",\"num_pages\":1,\"tag_ids\":[12345]}]}";

    private static final class Result implements GalleryDetailRequests.Callback {
        final CountDownLatch main = new CountDownLatch(1), related = new CountDownLatch(1), end = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        volatile Gallery gallery;
        volatile Exception error;

        @Override public void onSuccess(List<GenericGallery> galleries) {
            gallery = (Gallery) galleries.get(0);
            main.countDown();
        }
        @Override public void onRelated(List<SimpleGallery> items) {
            gallery.setRelated(items);
            related.countDown();
        }
        @Override public void onFailure(Exception exception) { error = exception; failed.countDown(); }
        @Override public void onEnd() { end.countDown(); }
    }

    @Test
    public void sharesStagesWithLateSubscriberAndIndependentSnapshots() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch tagsEntered = new CountDownLatch(1), releaseTags = new CountDownLatch(1);
        AtomicInteger detailCalls = new AtomicInteger();
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean tags = chain.request().url().encodedPath().endsWith("/tags/ids");
                if (tags) {
                    tagsEntered.countDown();
                    await(releaseTags);
                } else {
                    detailCalls.incrementAndGet();
                    entered.countDown();
                    await(release);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(tags
                        ? "[{\"id\":12345,\"name\":\"artist\",\"type\":\"artist\",\"count\":1}]"
                        : DETAIL, null)).build();
            }).build();
            Result canceled = new Result(), first = new Result(), late = new Result();
            GalleryDetailRequests.Subscription discarded = GalleryDetailRequests.open(app, 117, canceled);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            GalleryDetailRequests.Subscription active = GalleryDetailRequests.open(app, 117, first);
            discarded.cancelRequest();
            release.countDown();
            assertTrue(first.main.await(5, TimeUnit.SECONDS));
            assertTrue(tagsEntered.await(5, TimeUnit.SECONDS));
            GalleryDetailRequests.Subscription arriving = GalleryDetailRequests.open(app, 117, late);
            assertTrue(late.main.await(5, TimeUnit.SECONDS));
            assertEquals(1, detailCalls.get());
            assertNotSame(first.gallery, late.gallery);
            first.gallery.getGalleryData().getPages().get(0).setImagePath(Uri.parse("https://example.test/changed"));
            assertFalse(first.gallery.getHighPage(0).equals(late.gallery.getHighPage(0)));
            releaseTags.countDown();
            assertTrue(first.related.await(5, TimeUnit.SECONDS));
            assertTrue(late.related.await(5, TimeUnit.SECONDS));
            assertTrue(first.end.await(5, TimeUnit.SECONDS));
            assertTrue(late.end.await(5, TimeUnit.SECONDS));
            assertEquals(1, first.gallery.getRelated().size());
            assertEquals(1, late.gallery.getRelated().size());
            assertEquals(1, canceled.main.getCount());
            active.cancelRequest();
            arriving.cancelRequest();
        } finally {
            release.countDown();
            releaseTags.countDown();
            Global.client = previous;
        }
    }

    @Test
    public void failingSubscriberDoesNotPreventOtherDelivery() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                await(release);
                String noRelated = DETAIL.substring(0, DETAIL.indexOf(",\"related\"")) + "}";
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(noRelated, null)).build();
            }).build();
            Result other = new Result();
            GalleryDetailRequests.Callback broken = new GalleryDetailRequests.Callback() {
                @Override public void onSuccess(List<GenericGallery> galleries) { throw new IllegalStateException("Test callback"); }
                @Override public void onRelated(List<SimpleGallery> related) { }
                @Override public void onFailure(Exception error) { }
                @Override public void onEnd() { }
            };
            GalleryDetailRequests.open(app, 117, broken);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            GalleryDetailRequests.open(app, 117, other);
            release.countDown();
            assertTrue(other.main.await(5, TimeUnit.SECONDS));
            assertTrue(other.end.await(5, TimeUnit.SECONDS));
            assertEquals(117, other.gallery.getId());
        } finally {
            release.countDown();
            Global.client = previous;
        }
    }

    @Test
    public void accountChangeSeparatesRequestsAndRejectsOldResult() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            AuthStore.clear(app);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                }
                String noRelated = DETAIL.substring(0, DETAIL.indexOf(",\"related\"")) + "}";
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(noRelated, null)).build();
            }).build();
            Result old = new Result(), current = new Result();
            GalleryDetailRequests.open(app, 117, old);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AuthStore.saveApiKey(app, "test-identity", true);
            GalleryDetailRequests.open(app, 117, current);
            assertTrue(current.main.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(old.failed.await(5, TimeUnit.SECONDS));
            assertTrue(old.end.await(5, TimeUnit.SECONDS));
            assertEquals(1, old.main.getCount());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            AuthStore.clear(app);
            Global.client = previous;
        }
    }

    @Test
    public void mirrorChangeStartsSeparateRequestAndRejectsOldResult() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            String originalMirror = Global.getMirror();
            try {
                Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        await(release);
                    }
                    String noRelated = DETAIL.substring(0, DETAIL.indexOf(",\"related\"")) + "}";
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(ResponseBody.create(noRelated, null)).build();
                }).build();
                Result old = new Result(), current = new Result();
                GalleryDetailRequests.open(app, 117, old);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                ReflectionHelpers.callStaticMethod(Global.class, "setMirror",
                    ReflectionHelpers.ClassParameter.from(String.class, "other.example"));
                GalleryDetailRequests.open(app, 117, current);
                assertTrue(current.main.await(5, TimeUnit.SECONDS));
                assertTrue(current.end.await(5, TimeUnit.SECONDS));
                ReflectionHelpers.callStaticMethod(Global.class, "setMirror",
                    ReflectionHelpers.ClassParameter.from(String.class, originalMirror));
                Result returned = new Result();
                GalleryDetailRequests.open(app, 117, returned);
                assertTrue(returned.main.await(5, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(old.failed.await(5, TimeUnit.SECONDS));
                assertTrue(old.end.await(5, TimeUnit.SECONDS));
                assertEquals(1, old.main.getCount());
                assertEquals(3, calls.get());
            } finally {
                ReflectionHelpers.callStaticMethod(Global.class, "setMirror",
                    ReflectionHelpers.ClassParameter.from(String.class, originalMirror));
            }
        } finally {
            release.countDown();
            Global.client = previous;
        }
    }

    @Test
    public void lastCancellationStopsUnderlyingCallAndNextOpenStartsAgain() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        OkHttpClient previous = Global.client;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Call> firstCall = new AtomicReference<>();
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                if (calls.incrementAndGet() == 1) {
                    firstCall.set(chain.call());
                    entered.countDown();
                    await(release);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("offline").body(ResponseBody.create("{}", null)).build();
            }).build();
            Result first = new Result(), second = new Result();
            GalleryDetailRequests.Subscription one = GalleryDetailRequests.open(app, 117, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            GalleryDetailRequests.Subscription two = GalleryDetailRequests.open(app, 117, second);
            one.cancelRequest();
            assertFalse(firstCall.get().isCanceled());
            two.cancelRequest();
            assertTrue(firstCall.get().isCanceled());
            Result fresh = new Result();
            GalleryDetailRequests.open(app, 117, fresh);
            assertTrue(fresh.end.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertTrue(fresh.error != null);
        } finally {
            release.countDown();
            Global.client = previous;
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }
}
