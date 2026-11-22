package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.components.views.PageSwitcher;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
public class SearchPaginationTest {
    @Test public void filteredPagesCanBeAdvancedManuallyWithInfiniteScroll() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = app.getSharedPreferences("Settings", 0);
        String exactKey = app.getString(R.string.preference_key_exact_title_match);
        String scrollKey = app.getString(R.string.key_infinite_scroll_main);
        preferences.edit().putBoolean(exactKey, true).putBoolean(scrollKey, true).commit();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        boolean firstTime = ReflectionHelpers.getStaticField(MainActivity.class, "firstTime");
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", false);
        List<String> requestedPages = Collections.synchronizedList(new ArrayList<>());
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String page = chain.request().url().queryParameter("page");
            requestedPages.add(page);
            int tagId = "3".equals(page) || "5".equals(page) ? 900001 : 900002;
            String body = "{\"num_pages\":5,\"result\":[{\"id\":" + page
                + ",\"media_id\":\"21\",\"tag_ids\":[" + tagId + "]}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create(body, null)).build();
        }).build();
        String packageName = app.getPackageName();
        Intent search = new Intent(app, MainActivity.class)
            .putExtra(packageName + ".SEARCHMODE", true)
            .putExtra(packageName + ".QUERY", "")
            .putExtra(packageName + ".ADVANCED", true)
            .putParcelableArrayListExtra(packageName + ".TAGS", new ArrayList<>(Collections.singleton(
                new Tag("required", 0, 900001, TagType.TAG, TagStatus.ACCEPTED))));
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class, search).setup()) {
                MainActivity activity = controller.get();
                PageSwitcher switcher = activity.findViewById(R.id.page_switcher);
                View next = switcher.findViewById(R.id.next);
                RecyclerView recycler = activity.findViewById(R.id.recycler);

                for (int page = 1; page <= 2; page++) {
                    awaitResults(activity);
                    assertEquals(0, activity.adapter.getItemCount());
                    assertEquals(page, requestedPages.size());
                    assertEquals(page, switcher.getActualPage());
                    assertEquals(View.VISIBLE, switcher.getVisibility());
                    assertTrue(next.isEnabled());
                    assertTrue(next.performClick());
                }

                awaitResults(activity);
                assertEquals(1, activity.adapter.getItemCount());
                // Layout of the single result can already trigger the next scroll request.
                if (currentRequest(activity).getPage() == 3) {
                    assertEquals(View.GONE, switcher.getVisibility());
                    dispatchScroll(recycler);
                }
                awaitResults(activity);
                assertEquals(Arrays.asList("1", "2", "3", "4"), requestedPages);
                assertEquals(1, activity.adapter.getItemCount());
                assertEquals(4, switcher.getActualPage());
                assertEquals(View.VISIBLE, switcher.getVisibility());
                dispatchScroll(recycler);
                assertEquals(4, currentRequest(activity).getPage());
                assertEquals(4, requestedPages.size());
                assertTrue(next.performClick());

                awaitResults(activity);
                assertEquals(Arrays.asList("1", "2", "3", "4", "5"), requestedPages);
                assertEquals(2, activity.adapter.getItemCount());
                assertEquals(View.GONE, switcher.getVisibility());
                dispatchScroll(recycler);
                assertEquals(5, currentRequest(activity).getPage());
                assertEquals(5, requestedPages.size());
            }
        } finally {
            Global.client = previous;
            ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", firstTime);
            preferences.edit().remove(exactKey).remove(scrollKey).commit();
            Global.initFromShared(app);
        }
    }

    private static void dispatchScroll(RecyclerView recycler) {
        ReflectionHelpers.callInstanceMethod(RecyclerView.class, recycler, "dispatchOnScrolled",
            ReflectionHelpers.ClassParameter.from(int.class, 0),
            ReflectionHelpers.ClassParameter.from(int.class, 1));
    }

    private static void awaitResults(MainActivity activity) throws InterruptedException {
        InspectorV3 request = currentRequest(activity);
        request.join(5000);
        assertFalse(request.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static InspectorV3 currentRequest(MainActivity activity) {
        return ReflectionHelpers.getField(activity, "inspector");
    }
}
