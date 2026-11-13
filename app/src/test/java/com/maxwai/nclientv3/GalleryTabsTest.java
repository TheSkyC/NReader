// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.os.Looper;
import android.util.JsonReader;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.maxwai.nclientv3.adapters.CommentAdapter;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.api.comments.CommentsFetcher;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryTabsTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void switchesTabsAndRestoresSelection() throws Exception {
        File folder = temporaryFolder.newFolder("gallery");
        assertEquals(true, new File(folder, "1.jpg").createNewFile());
        Application application = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(application, GalleryActivity.class);
        intent.putExtra(application.getPackageName() + ".GALLERY", new LocalGallery(folder, true));
        intent.putExtra(application.getPackageName() + ".ISLOCAL", true);
        application.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                TabLayout tabs = activity.findViewById(R.id.gallery_tabs);
                assertEquals(3, tabs.getTabCount());
                assertEquals(Color.BLACK, tabs.getTabTextColors().getColorForState(
                    new int[]{android.R.attr.state_selected}, Color.TRANSPARENT));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_details).getVisibility());
                CountDownLatch loaded = new CountDownLatch(1);
                ReadingProgress.load(-1, ignored -> loaded.countDown());
                assertTrue(loaded.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View read = activity.findViewById(R.id.gallery_read);
                assertNotNull(read);
                assertTrue(read.isEnabled());
                read.performClick();
                Intent reader = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(reader);
                assertEquals(ZoomActivity.class.getName(), reader.getComponent().getClassName());
                assertEquals(1, reader.getIntExtra(application.getPackageName() + ".PAGE", -1));
                assertNotNull(tabs.getTabAt(1));
                tabs.getTabAt(1).select();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_preview).getVisibility());
                assertEquals(View.INVISIBLE, activity.findViewById(R.id.gallery_details).getVisibility());
                controller.recreate();
                activity = controller.get();
                tabs = activity.findViewById(R.id.gallery_tabs);
                assertEquals(1, tabs.getSelectedTabPosition());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_preview).getVisibility());
                ReflectionHelpers.setField(activity, "readerOpened", true);
                ReflectionHelpers.setField(activity, "restoringPosition", true);
                ReflectionHelpers.setField(activity, "activeScrollTarget", new ReadingPosition(1, 0.5f));
                tabs.getTabAt(2).select();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.gallery_comments).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.comments_message).getVisibility());
                ArrayList<Comment> comments = new ArrayList<>();
                comments.add(new Comment(new JsonReader(new StringReader("{\"id\":1,\"post_date\":1,"
                    + "\"body\":\"test\",\"poster\":{\"id\":2,\"username\":\"reader\",\"avatar_url\":\"\"}}"))));
                CommentAdapter commentsAdapter = new CommentAdapter(activity, comments, true);
                ((RecyclerView) activity.findViewById(R.id.comments_list)).setAdapter(commentsAdapter);
                ReflectionHelpers.setField(activity, "commentsAdapter", commentsAdapter);
                ReflectionHelpers.setField(activity, "commentsLoaded", true);
                ReflectionHelpers.setField(activity, "commentsPage", 2);
                ReflectionHelpers.setField(activity, "commentsPages", 3);
                controller.recreate();
                activity = controller.get();
                tabs = activity.findViewById(R.id.gallery_tabs);
                assertEquals(2, tabs.getSelectedTabPosition());
                assertEquals(2, ((RecyclerView) activity.findViewById(R.id.comments_list)).getAdapter().getItemCount());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.comments_more).getVisibility());
            }
        }
    }

    @Test
    public void queuedCommentResultDoesNotUpdateDestroyedActivity() throws Exception {
        File folder = temporaryFolder.newFolder("destroyed-gallery");
        assertTrue(new File(folder, "1.jpg").createNewFile());
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        Intent intent = new Intent(application, GalleryActivity.class);
        intent.putExtra(application.getPackageName() + ".GALLERY", new LocalGallery(folder, true));
        intent.putExtra(application.getPackageName() + ".ISLOCAL", true);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup();
            GalleryActivity activity = controller.get();
            Class<?> callbackClass = Class.forName(GalleryActivity.class.getName() + "$CommentsResultCallback");
            Constructor<?> constructor = callbackClass.getDeclaredConstructor(GalleryActivity.class, int.class);
            constructor.setAccessible(true);
            CommentsFetcher.Callback callback = (CommentsFetcher.Callback) constructor.newInstance(activity, 1);
            callback.onResult(new ArrayList<>(), 0, true);
            controller.pause().stop().destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(false, ReflectionHelpers.getField(activity, "commentsLoaded"));
        }
    }

    @Test
    public void rotationCancelsAndRestartsInFlightCommentPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        CountDownLatch secondPageEntered = new CountDownLatch(1);
        CountDownLatch releaseOldPage = new CountDownLatch(1);
        AtomicInteger secondPageRequests = new AtomicInteger();
        AtomicReference<Call> oldCall = new AtomicReference<>();
        OkHttpClient previousClient = Global.client;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
                if (!chain.request().url().encodedPath().endsWith("/comments")) {
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Synthetic response").body(ResponseBody.create("{}", null)).build();
                }
                String page = chain.request().url().queryParameter("page");
                if ("2".equals(page) && secondPageRequests.incrementAndGet() == 1) {
                    oldCall.set(chain.call());
                    secondPageEntered.countDown();
                    try {
                        if (!releaseOldPage.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(exception);
                    }
                }
                String body = "{\"num_pages\":2,\"result\":[{\"id\":" + ("2".equals(page) ? 2 : 1)
                    + ",\"post_date\":1,\"body\":\"test\",\"poster\":{\"id\":1,\"username\":\"reader\"}}]}";
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(body, null)).build();
            }).build();
            Gallery gallery = new Gallery(application, "{\"id\":117,\"num_pages\":1,"
                + "\"title\":{\"pretty\":\"Test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
                + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
                + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}", null, false);
            Intent intent = new Intent(application, GalleryActivity.class);
            intent.putExtra(application.getPackageName() + ".GALLERY", gallery);
            try (ActivityController<GalleryActivity> controller = Robolectric.buildActivity(GalleryActivity.class, intent).setup()) {
                GalleryActivity activity = controller.get();
                ((TabLayout) activity.findViewById(R.id.gallery_tabs)).getTabAt(2).select();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(ReflectionHelpers.getField(activity, "commentsLoaded"))
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals(2, ((RecyclerView) activity.findViewById(R.id.comments_list)).getAdapter().getItemCount());
                activity.findViewById(R.id.comments_more).performClick();
                assertTrue(secondPageEntered.await(5, TimeUnit.SECONDS));
                controller.recreate();
                assertTrue(oldCall.get().isCanceled());
                activity = controller.get();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (((Integer) ReflectionHelpers.getField(activity, "commentsPage")) != 2
                    && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals(2, ((RecyclerView) activity.findViewById(R.id.comments_list)).getAdapter().getItemCount());
                assertEquals(2, secondPageRequests.get());
            } finally {
                releaseOldPage.countDown();
            }
        } finally {
            Global.client = previousClient;
        }
    }
}
