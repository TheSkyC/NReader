package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.MenuItem;

import com.google.android.material.navigation.NavigationView;
import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagRuleEdits;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityTagRulesTest {
    private final BlockingQueue<HttpUrl> requests = new LinkedBlockingQueue<>();
    private final Tag tag = new Tag("rule-fixture", 50, 500_001, TagType.TAG, TagStatus.DEFAULT);
    private DatabaseHelper helper;
    private SQLiteDatabase database;
    private Field firstTime;
    private boolean previousFirstTime;
    private OkHttpClient previousClient;
    private Application application;

    @Before public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        Global.initFromShared(application);
        Login.initLogin(application);
        firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        previousFirstTime = firstTime.getBoolean(null);
        firstTime.setBoolean(null, false);
        previousClient = Global.client;
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request().url());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build();
        }).build();
        helper = new DatabaseHelper(application);
        database = helper.getWritableDatabase();
        Queries.TagTable.insert(tag);
    }

    @After public void tearDown() throws Exception {
        Global.client = previousClient;
        firstTime.setBoolean(null, previousFirstTime);
        helper.close();
    }

    @Test public void homeRefreshesAfterReturningBeforeQueuedRuleCommits() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity home = controller.get();
            await(() -> requests.stream().anyMatch(url -> url.encodedPath().endsWith("/galleries")));
            NavigationView navigation = home.findViewById(R.id.nav_view);
            MenuItem editor = navigation.getMenu().findItem(R.id.tag_manager);
            assertTrue(home.onNavigationItemSelected(editor));
            controller.pause();
            AtomicReference<TagRuleEdits.Result> committed = new AtomicReference<>();
            database.beginTransactionNonExclusive();
            try {
                TagRuleEdits.cycle(tag, true, committed::set);
                controller.resume();
                assertEquals(TagStatus.DEFAULT, Queries.TagTable.getStatus(tag));
                assertNull(committed.get());
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            await(() -> committed.get() != null);
            assertEquals(TagRuleEdits.Outcome.SAVED, committed.get().outcome);
            await(() -> requests.stream().map(url -> url.queryParameter("query")).filter(java.util.Objects::nonNull)
                .anyMatch(query -> tag.toQueryTag(TagStatus.ACCEPTED).equals(query.trim())));
        }
    }

    @Test public void savedAvoidedRuleDoesNotChangePositiveTagQueryOnNextPage() throws Exception {
        tag.setStatus(TagStatus.AVOIDED);
        assertEquals(1, Queries.TagTable.updateTag(tag));
        tag.setStatus(TagStatus.DEFAULT);
        String appId = application.getPackageName();
        Intent launch = new Intent(application, MainActivity.class)
            .putExtra(appId + ".ISBYTAG", true).putExtra(appId + ".TAG", tag);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, launch).setup()) {
            MainActivity activity = controller.get();
            Field displayedStatus = MainActivity.class.getDeclaredField("displayedRuleStatus");
            displayedStatus.setAccessible(true);
            await(() -> value(displayedStatus, activity) == TagStatus.AVOIDED);
            Field request = MainActivity.class.getDeclaredField("inspector");
            request.setAccessible(true);
            InspectorV3 inspector = (InspectorV3) request.get(activity);
            inspector.setPage(2);
            String query = HttpUrl.get(inspector.getUrl()).queryParameter("query");
            assertTrue(query.contains(tag.toQueryTag(TagStatus.ACCEPTED)));
            assertFalse(query.contains(tag.toQueryTag(TagStatus.AVOIDED)));
        }
    }

    private static Object value(Field field, MainActivity activity) {
        try {
            return field.get(activity);
        } catch (IllegalAccessException error) {
            throw new AssertionError(error);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.yield();
        }
        assertTrue("Timed out waiting for the activity to load tag rules", condition.getAsBoolean());
    }
}
