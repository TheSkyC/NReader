package com.theskyc.nreader;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.async.database.DatabaseHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RandomActivityLoadingTest {
    @Test
    public void destroyingActivityIgnoresLateFailure() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            throw new IOException("fixture failure");
        }).build();
        RandomActivity.loadedGallery = null;
        ActivityController<RandomActivity> controller = Robolectric.buildActivity(RandomActivity.class).setup();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            TextView state = controller.get().findViewById(R.id.random_state);
            controller.pause().stop().destroy();
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(app.getString(R.string.random_gallery_loading), state.getText().toString());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            Global.client = previous;
            RandomActivity.loadedGallery = null;
        }
    }

    @Test
    public void successDisplaysGalleryAndLaterFailureKeepsItAvailable() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        AtomicInteger calls = new AtomicInteger();
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (calls.incrementAndGet() > 1) throw new IOException("fixture failure");
            String body = "{\"id\":117,\"media_id\":1,\"num_pages\":1,"
                + "\"title\":{\"pretty\":\"Test gallery\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
                + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
                + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create(body, null)).build();
        }).build();
        RandomActivity.loadedGallery = null;
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (ActivityController<RandomActivity> controller = Robolectric.buildActivity(RandomActivity.class).setup()) {
                RandomActivity activity = controller.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (RandomActivity.loadedGallery == null && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(RandomActivity.loadedGallery != null);
                assertEquals(View.GONE, activity.findViewById(R.id.random_state).getVisibility());
                assertTrue(activity.findViewById(R.id.favorite).isEnabled());
                assertEquals("Test gallery", ((TextView) activity.findViewById(R.id.title)).getText().toString());
                View shuffle = activity.findViewById(R.id.shuffle);
                shuffle.performClick();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!shuffle.isEnabled() && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(shuffle.isEnabled());
                assertEquals("Test gallery", ((TextView) activity.findViewById(R.id.title)).getText().toString());
                assertTrue(activity.findViewById(R.id.favorite).isEnabled());
            }
        } finally {
            Global.client = previous;
            RandomActivity.loadedGallery = null;
        }
    }

    @Test
    public void failureStopsRetriesAndShuffleStartsOneNewRequest() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            throw new IOException("fixture failure");
        }).build();
        RandomActivity.loadedGallery = null;
        try (ActivityController<RandomActivity> controller = Robolectric.buildActivity(RandomActivity.class).setup()) {
            RandomActivity activity = controller.get();
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            TextView state = activity.findViewById(R.id.random_state);
            View shuffle = activity.findViewById(R.id.shuffle);
            assertEquals(activity.getString(R.string.random_gallery_loading), state.getText().toString());
            assertFalse(shuffle.isEnabled());
            assertFalse(activity.findViewById(R.id.favorite).isEnabled());
            assertEquals(View.INVISIBLE, activity.findViewById(R.id.favorite).getVisibility());
            releaseFirst.countDown();
            awaitFailure(activity);
            assertEquals(1, calls.get());
            assertTrue(shuffle.isEnabled());
            shuffle.performClick();
            assertFalse(shuffle.isEnabled());
            awaitFailure(activity);
            assertEquals(2, calls.get());
        } finally {
            releaseFirst.countDown();
            Global.client = previous;
            RandomActivity.loadedGallery = null;
        }
    }

    private static void awaitFailure(RandomActivity activity) throws Exception {
        TextView state = activity.findViewById(R.id.random_state);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!activity.getString(R.string.random_gallery_load_failed).contentEquals(state.getText())
            && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(activity.getString(R.string.random_gallery_load_failed), state.getText().toString());
    }
}
