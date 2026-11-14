package com.maxwai.nclientv3.api;

import android.app.Application;

import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class InspectorResultTest {
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
