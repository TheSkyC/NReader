// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.Menu;
import android.view.View;

import androidx.appcompat.widget.Toolbar;

import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryOpeningTest {
    private static final String DETAIL = "{\"id\":117,\"num_pages\":1,"
        + "\"title\":{\"pretty\":\"Test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
        + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
        + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}";

    @Test
    public void shellDisablesActionsUntilDetailArrives() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Gallery full = new Gallery(application, DETAIL, null, false);
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", new SimpleGallery(full));
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Test", activity.getTitle().toString());
                assertFalse(activity.findViewById(R.id.gallery_read).isEnabled());
                Menu menu = ((Toolbar) activity.findViewById(R.id.toolbar)).getMenu();
                activity.onCreateOptionsMenu(menu);
                assertFalse(menu.findItem(R.id.manage_status).isVisible());
                assertEquals(View.GONE, activity.findViewById(R.id.gallery_metadata).getVisibility());

                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "detailReady"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertTrue(ReflectionHelpers.getField(activity, "detailReady"));
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }

    @Test
    public void destroyingShellCancelsDetailRequest() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Call> call = new AtomicReference<>();
        OkHttpClient previous = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                call.set(chain.call());
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(DETAIL, null)).build();
            }).build();
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY",
                new SimpleGallery(new Gallery(application, DETAIL, null, false)));
            ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                controller.pause().stop().destroy();
                assertTrue(call.get().isCanceled());
            } finally {
                release.countDown();
            }
        } finally {
            Global.client = previous;
        }
    }
}
