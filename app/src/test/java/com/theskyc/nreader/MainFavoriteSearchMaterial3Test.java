package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.settings.AccountRuntimeTestScope;
import com.theskyc.nreader.settings.Global;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class MainFavoriteSearchMaterial3Test {
    @Rule public final AccountRuntimeTestScope accountRuntimeScope = new AccountRuntimeTestScope();

    @Test public void onlineFavoriteSearchSubmitsAndRestoresDraftSeparately() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        OkHttpClient previous = Global.client;
        boolean firstTime = ReflectionHelpers.getStaticField(MainActivity.class, "firstTime");
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", false);
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(ResponseBody.create("{\"num_pages\":1,\"result\":[]}", null)).build()).build();
        Intent intent = new Intent(app, MainActivity.class).putExtra(app.getPackageName() + ".FAVORITE", true);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Bundle state = new Bundle();
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
                MainActivity activity = controller.get();
                SearchBar bar = activity.findViewById(R.id.favorite_search_bar);
                SearchView search = activity.findViewById(R.id.favorite_search_view);
                assertTrue(bar.isShown());
                assertFalse(bar.getMenu().findItem(R.id.search).isVisible());
                bar.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                search.getEditText().setText("submitted");
                search.getEditText().onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                InspectorV3 inspector = ReflectionHelpers.getField(activity, "inspector");
                assertTrue(inspector.getUrl().contains("q=submitted"));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                bar.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                search.getEditText().setText("unfinished draft");
                activity.onSaveInstanceState(state);
            }
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent)
                .create(state).start().resume().visible()) {
                MainActivity restored = controller.get();
                SearchView search = restored.findViewById(R.id.favorite_search_view);
                InspectorV3 inspector = ReflectionHelpers.getField(restored, "inspector");
                assertTrue(inspector.getUrl().contains("q=submitted"));
                assertTrue(search.isShowing());
                assertEquals("unfinished draft", search.getEditText().getText().toString());
                restored.getOnBackPressedDispatcher().onBackPressed();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(restored.isFinishing());
                assertEquals("submitted", search.getEditText().getText().toString());
            }
        } finally {
            Global.client = previous;
            ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", firstTime);
        }
    }
}
