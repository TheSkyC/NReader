package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SearchView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(AndroidJUnit4.class)
public class TagSearchActivityDeliveryTest {
    private static final int MANUAL_ID = 58001;
    private static final int AUTOMATIC_ID = 58002;
    private static final String MANUAL_NAME = "manual selection";
    private static final String AUTOMATIC_NAME = "automatic selection";
    private static final String SEARCH = "device fixture";

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    @Test
    public void accountExclusionsCrossActivityManagerAndStaleRevisionPausesAfterRecreation() throws Exception {
        prepareFixture();
        SearchActivity form = (SearchActivity) instrumentation.startActivitySync(
            new Intent(context, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        try {
            awaitOnMain("account exclusions to appear in the form",
                () -> isExcluded(chip(form, MANUAL_ID)) && isExcluded(chip(form, AUTOMATIC_ID)));
            instrumentation.runOnMainSync(() -> {
                ChipTag manual = chip(form, MANUAL_ID);
                assertTrue(manual.performClick());
                assertTrue(manual.performClick());
                assertEquals(TagStatus.ACCEPTED, manual.getTag().getStatus());
                ((SearchView) form.findViewById(R.id.search)).setQuery(SEARCH, true);
            });
            MainActivity result = awaitResults(null);
            String original = takeQuery();
            assertManualTerms(original);
            assertTrue(original.contains("-tag:\"" + AUTOMATIC_NAME + "\""));
            awaitOnMain("loaded result", () -> result.getRecycler().getAdapter().getItemCount() == 1);

            replaceBlacklist(Collections.singletonList(tag(58003, "new account exclusion")));
            awaitOnMain("confirmation before recreation", () -> fallbackVisible(result));
            instrumentation.runOnMainSync(result::recreate);
            MainActivity recreated = awaitResults(result);
            awaitOnMain("confirmation after recreation", () -> {
                assertNull("Recreation dispatched a request before confirmation", requests.peek());
                return fallbackVisible(recreated);
            });
            assertNull("Recreation dispatched a request before confirmation", requests.poll(1, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> assertTrue(fallbackAction(recreated).performClick()));
            String fallback = takeQuery();
            assertManualTerms(fallback);
            assertFalse(fallback.contains(AUTOMATIC_NAME));
            assertFalse(fallback.contains("new account exclusion"));
            awaitOnMain("reloaded result", () -> recreated.getRecycler().getAdapter().getItemCount() == 1);
        } finally {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MainActivity || activity == form) activity.finishAndRemoveTask();
                }
            });
            instrumentation.waitForIdleSync();
        }
    }

    private void prepareFixture() throws ReflectiveOperationException {
        Field firstTime = MainActivity.class.getDeclaredField("firstTime");
        firstTime.setAccessible(true);
        // Keep the database open for background work until the isolated process exits.
        Database.setDatabase(new DatabaseHelper(context).getWritableDatabase());
        int noImages = Global.DataUsageType.NONE.ordinal();
        assertTrue(context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .putBoolean(context.getString(R.string.preference_key_use_account_tag), true)
            .putInt(context.getString(R.string.key_wifi_usage), noImages)
            .putInt(context.getString(R.string.key_mobile_usage), noImages).commit());
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            requests.add(request);
            if (!request.url().encodedPath().endsWith("/search")) {
                throw new IOException("Unexpected endpoint in device search test");
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(ResponseBody.create(
                    "{\"num_pages\":1,\"result\":[{\"id\":71,\"media_id\":\"21\",\"tag_ids\":[58001]}]}", null))
                .build();
        }).build();
        AuthStore.saveApiKey(context, "tag-search-device-fixture", true);
        Global.initFromShared(context);
        // The isolated runner skips application startup; suppress the unrelated update jobs.
        firstTime.setBoolean(null, false);
        replaceBlacklist(Arrays.asList(tag(MANUAL_ID, MANUAL_NAME), tag(AUTOMATIC_ID, AUTOMATIC_NAME)));
        assertTrue("Account tag preference and revision must be active", Login.useAccountTag());
        assertEquals(2, Queries.TagTable.getAllOnlineBlacklisted().size());
    }

    private static Tag tag(int id, String name) {
        return new Tag(name, 100, id, TagType.TAG, TagStatus.DEFAULT);
    }

    private void replaceBlacklist(List<Tag> tags) {
        String identity = AuthStore.activeIdentity(context);
        assertNotNull("Synthetic account identity", identity);
        assertTrue(Login.replaceOnlineTags(context, identity, Login.beginOnlineTagsLoad(context, identity), tags));
    }

    private String takeQuery() throws InterruptedException {
        Request request = requests.poll(15, TimeUnit.SECONDS);
        assertNotNull("Expected search request", request);
        assertTrue("Unexpected endpoint: " + request.url().encodedPath(),
            request.url().encodedPath().endsWith("/search"));
        String query = request.url().queryParameter("query");
        assertNotNull("Expected a query parameter", query);
        return query;
    }

    private static void assertManualTerms(String query) {
        assertTrue(query.contains(SEARCH));
        assertTrue(query.contains("tag:\"" + MANUAL_NAME + "\""));
        assertFalse(query.contains("-tag:\"" + MANUAL_NAME + "\""));
    }

    private MainActivity awaitResults(MainActivity previous) {
        AtomicReference<MainActivity> result = new AtomicReference<>();
        awaitOnMain("resumed results", () -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof MainActivity && activity != previous) {
                    result.set((MainActivity) activity);
                    return true;
                }
            }
            return false;
        });
        return result.get();
    }

    private void awaitOnMain(String description, BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static ChipTag chip(SearchActivity form, int id) {
        ViewGroup group = form.findViewById(R.id.tag_group);
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ChipTag && ((ChipTag) child).getTag().getId() == id) return (ChipTag) child;
        }
        return null;
    }

    private static boolean fallbackVisible(MainActivity activity) {
        View action = fallbackAction(activity);
        return action instanceof TextView && action.isShown()
            && activity.getString(R.string.online_blacklist_search_without).contentEquals(((TextView) action).getText());
    }

    private static boolean isExcluded(ChipTag chip) {
        return chip != null && chip.getTag().getStatus() == TagStatus.AVOIDED;
    }

    private static View fallbackAction(MainActivity activity) {
        return activity.findViewById(com.google.android.material.R.id.snackbar_action);
    }
}
