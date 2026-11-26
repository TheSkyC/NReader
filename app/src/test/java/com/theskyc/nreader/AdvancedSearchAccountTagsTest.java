package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.snackbar.Snackbar;
import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.SearchPlanStore;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.settings.AuthSessionStore;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Database;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AdvancedSearchAccountTagsTest {
    private static final int MANUAL_ID = 58001;
    private static final int AUTOMATIC_ID = 58002;
    private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
    private Application app;
    private DatabaseHelper database;
    private OkHttpClient previousClient;
    private boolean firstTime;
    private SharedPreferences settings;
    private String setting;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        previousClient = Global.client;
        firstTime = ReflectionHelpers.getStaticField(MainActivity.class, "firstTime");
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", false);
        AuthSessionStore.clear(app);
        AuthStore.clear(app);
        settings = app.getSharedPreferences("Settings", 0);
        setting = app.getString(R.string.preference_key_use_account_tag);
        settings.edit().putBoolean(setting, true).commit();
        Global.initFromShared(app);
        AuthStore.saveApiKey(app, "advanced-search-fixture", true);
        database = new DatabaseHelper(app);
        Database.setDatabase(database.getWritableDatabase());
        replaceBlacklist(Arrays.asList(
            new Tag("manual selection", 100000, MANUAL_ID, TagType.TAG, TagStatus.DEFAULT),
            new Tag("automatic selection", 1, AUTOMATIC_ID, TagType.TAG, TagStatus.DEFAULT)));
        Global.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            HttpUrl url = chain.request().url();
            if (!url.encodedPath().endsWith("/search")) throw new IOException("Unexpected endpoint");
            queries.add(url.queryParameter("query"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(ResponseBody.create("{\"num_pages\":1,\"result\":[{\"id\":71,\"media_id\":\"21\",\"tag_ids\":[58001]}]}", null))
                .build();
        }).build();
    }

    @After public void tearDown() {
        Global.client = previousClient;
        ReflectionHelpers.setStaticField(MainActivity.class, "firstTime", firstTime);
        AuthStore.clear(app);
        Login.updateUser(null);
        settings.edit().remove(setting).commit();
        if (database != null) database.close();
        ReflectionHelpers.setStaticField(Database.class, "database", null);
        Queries.setDb(null);
    }

    @Test public void staleRevisionBetweenSubmissionAndLaunchRequiresExplicitFallback() throws Exception {
        Intent intent = submit();
        replaceBlacklist(Collections.emptyList());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            awaitSearch(activity);
            assertTrue(queries.isEmpty());

            chooseFallback(activity);
            awaitSearch(activity);
            assertEquals(1, queries.size());
            assertManualOnly(queries.get(0));
        }
    }

    @Test public void resultScreenRejectsMissingOrForeignAccountTags() throws Exception {
        Intent submitted = submit();
        for (boolean foreign : new boolean[]{false, true}) {
            Intent intent = new Intent(submitted);
            String key = app.getPackageName() + SearchActivity.ACCOUNT_TAGS_EXTRA;
            intent.removeExtra(key);
            if (foreign) {
                Bundle extras = new Bundle();
                extras.putBinder(key, new Binder());
                intent.putExtras(extras);
            }
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
                MainActivity activity = controller.get();
                awaitSearch(activity);
                assertTrue(queries.isEmpty());
                chooseFallback(activity);
                awaitSearch(activity);
                assertManualOnly(queries.get(0));
            }
            queries.clear();
        }
    }

    @Test public void acceptedFallbackSurvivesActivityRecreation() throws Exception {
        Intent intent = submit();
        replaceBlacklist(Collections.emptyList());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            chooseFallback(activity);
            awaitSearch(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, queries.size());
            assertManualOnly(queries.get(0));

            controller.recreate();
            awaitSearch(controller.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, queries.size());
            assertManualOnly(queries.get(1));
            assertEquals(1, controller.get().adapter.getItemCount());
        }
    }

    @Test public void activityRecreationWithoutConfirmationStillRequiresFallback() throws Exception {
        Intent intent = submit();
        replaceBlacklist(Collections.emptyList());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            awaitSearch(controller.get());
            assertTrue(queries.isEmpty());

            controller.recreate();
            MainActivity recreated = controller.get();
            awaitSearch(recreated);
            assertTrue(queries.isEmpty());

            chooseFallback(recreated);
            awaitSearch(recreated);
            assertEquals(1, queries.size());
            assertManualOnly(queries.get(0));
        }
    }

    @Test public void acceptedFallbackRestoresFromParceledStateWithoutTheHandoff() throws Exception {
        Intent intent = submit();
        replaceBlacklist(Collections.emptyList());
        Bundle state = new Bundle();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            chooseFallback(controller.get());
            awaitSearch(controller.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, controller.get().adapter.getItemCount());
            controller.saveInstanceState(state);
        }

        intent.removeExtra(app.getPackageName() + SearchActivity.ACCOUNT_TAGS_EXTRA);
        try (ActivityController<MainActivity> controller =
                 Robolectric.buildActivity(MainActivity.class, intent).setup(parcelState(state))) {
            awaitSearch(controller.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, queries.size());
            assertManualOnly(queries.get(1));
            assertEquals(1, controller.get().adapter.getItemCount());
        }
    }

    @Test public void acceptedBrowseFallbackKeepsTheOriginalTagAfterRecreation() throws Exception {
        verifyBrowseFallbackAfterRecreation(true);
    }

    @Test public void browseRecreationWhileAwaitingConfirmationDoesNotBindANewCache() throws Exception {
        verifyBrowseFallbackAfterRecreation(false);
    }

    private void verifyBrowseFallbackAfterRecreation(boolean confirmBeforeRecreation) throws Exception {
        settings.edit().putBoolean(app.getString(R.string.key_ignore_tags), false).commit();
        Global.initFromShared(app);
        Tag browsed = new Tag("manual selection", 100000, MANUAL_ID, TagType.TAG, TagStatus.DEFAULT);
        Tag automatic = new Tag("automatic selection", 1, AUTOMATIC_ID, TagType.TAG, TagStatus.DEFAULT);
        replaceBlacklist(Collections.singletonList(automatic));
        Intent intent = new Intent(app, MainActivity.class)
            .putExtra(app.getPackageName() + ".ISBYTAG", true)
            .putExtra(app.getPackageName() + ".TAG", browsed);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            awaitSearch(controller.get());
            assertTrue(queries.get(0).contains("-tag:\"automatic selection\""));

            replaceBlacklist(Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (confirmBeforeRecreation) {
                chooseFallback(controller.get());
                awaitSearch(controller.get());
            }
            replaceBlacklist(Collections.singletonList(automatic));

            controller.recreate();
            MainActivity recreated = controller.get();
            awaitSearch(recreated);
            if (!confirmBeforeRecreation) {
                assertEquals(1, queries.size());
                chooseFallback(recreated);
                awaitSearch(recreated);
            }
            int expectedQueries = confirmBeforeRecreation ? 3 : 2;
            assertEquals(expectedQueries, queries.size());
            assertTrue(queries.get(expectedQueries - 1).contains("tag:\"manual selection\""));
            assertFalse(queries.get(expectedQueries - 1).contains("automatic selection"));
            assertEquals("manual selection", recreated.getSupportActionBar().getTitle().toString());
        }
    }

    @Test public void advancedSearchDropsQueuedResultsAndSavesOnlyManualTags() throws Exception {
        Intent intent = submit();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            InspectorV3 request = awaitSearch(activity);
            assertEquals(1, queries.size());
            assertTrue(queries.get(0).contains("tag:\"manual selection\""));
            assertTrue(queries.get(0).contains("-tag:\"automatic selection\""));
            Queries.BookmarkTable.addBookmark(request);
            assertManualOnly(HttpUrl.get(Queries.BookmarkTable.getBookmarks().get(0).url).queryParameter("query"));

            AuthStore.saveApiKey(app, "another-advanced-search-fixture", true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, activity.adapter.getItemCount());
            assertEquals(1, queries.size());

            chooseFallback(activity);
            awaitSearch(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, activity.adapter.getItemCount());
            assertEquals(2, queries.size());
            assertManualOnly(queries.get(1));
        }
    }

    @Test public void plainFormSubmissionDoesNotAcquireBlacklistOnLaunch() throws Exception {
        settings.edit().putBoolean(setting, false).commit();
        Intent intent;
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            controller.get().setQuery("fixture", true);
            intent = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull(intent);
        }
        settings.edit().putBoolean(setting, true).commit();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            awaitSearch(controller.get());
            assertEquals(Collections.singletonList("fixture"), queries);
        }
    }

    @Test public void searchBarShowsSearchViewAndRestoresExpandedState() throws Exception {
        Bundle state = new Bundle();
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            awaitBlacklist(activity);
            SearchBar searchBar = activity.findViewById(R.id.toolbar);
            SearchView searchView = activity.findViewById(R.id.search_view);
            assertEquals(SearchView.TransitionState.HIDDEN, searchView.getCurrentTransitionState());

            searchBar.performClick();

            assertTrue(searchView.isShowing());
            controller.saveInstanceState(state);
            assertTrue(state.getBoolean("search.viewExpanded"));
            assertNotNull(state.getBooleanArray("search.expandedGroups"));
        }

        try (ActivityController<SearchActivity> controller =
                 Robolectric.buildActivity(SearchActivity.class).setup(parcelState(state))) {
            assertTrue(((SearchView) controller.get().findViewById(R.id.search_view)).isShowing());
        }
    }

    @Test public void materialSearchEditorSubmitsCurrentQuery() {
        settings.edit().putBoolean(setting, false).commit();
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            SearchView searchView = activity.findViewById(R.id.search_view);
            searchView.setText(" fixture ");

            searchView.getEditText().onEditorAction(EditorInfo.IME_ACTION_SEARCH);

            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals("fixture", intent.getStringExtra(app.getPackageName() + ".QUERY"));
        }
    }

    @Test public void emptyPortableSearchDoesNotBecomeAHomeBookmark() throws Exception {
        Intent intent = submitEmptySearch();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            InspectorV3 inspector = awaitSearch(controller.get());
            assertEquals(1, queries.size());
            assertTrue(queries.get(0).contains("-tag:\"automatic selection\""));
            Queries.BookmarkTable.addBookmark(inspector);
            InspectorV3 reopened = Queries.BookmarkTable.getBookmarks().get(0).createInspector(app, inspector.getResponse());
            assertEquals(ApiRequestType.BYSEARCH, reopened.getRequestType());
            assertEquals("", HttpUrl.get(reopened.getUrl()).queryParameter("query"));
        }
    }

    @Test public void pendingEmptySearchRemainsAFormSearchAfterSavedStateRoundTrip() throws Exception {
        Intent intent = submitEmptySearch();
        Bundle state = new Bundle();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            awaitSearch(controller.get());
            assertEquals(1, queries.size());
            replaceBlacklist(Collections.emptyList());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            controller.saveInstanceState(state);
        }

        intent.removeExtra(app.getPackageName() + SearchActivity.ACCOUNT_TAGS_EXTRA);
        try (ActivityController<MainActivity> controller =
                 Robolectric.buildActivity(MainActivity.class, intent).setup(parcelState(state))) {
            awaitSearch(controller.get());
            assertEquals(1, queries.size());
            chooseFallback(controller.get());
            InspectorV3 inspector = awaitSearch(controller.get());
            assertEquals(ApiRequestType.BYSEARCH, inspector.getRequestType());
            assertEquals(2, queries.size());
            assertEquals("", queries.get(1));
        }
    }

    private Intent submitEmptySearch() throws InterruptedException {
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            assertEquals(SearchView.TransitionState.HIDDEN,
                ((SearchView) activity.findViewById(R.id.search_view)).getCurrentTransitionState());
            awaitBlacklist(activity);
            ViewGroup group = activity.findViewById(R.id.tag_group);
            SearchUiTestSupport.selectStatus(chip(group, MANUAL_ID), 0);
            ReflectionHelpers.callInstanceMethod(activity, "submitSearch",
                ReflectionHelpers.ClassParameter.from(String.class, ""),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            return intent;
        }
    }

    private static Bundle parcelState(Bundle state) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(state);
            parcel.setDataPosition(0);
            return parcel.readBundle(MainActivity.class.getClassLoader());
        } finally {
            parcel.recycle();
        }
    }

    @Test public void savedPlanRetainsManualOverrideWithoutAutomaticSelection() throws Exception {
        Intent intent = submit();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).setup()) {
            MainActivity activity = controller.get();
            awaitSearch(activity);
            ReflectionHelpers.callInstanceMethod(activity, "saveSearchPlan");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            ViewGroup custom = dialog.findViewById(androidx.appcompat.R.id.custom);
            ((EditText) custom.findViewById(R.id.plan_name)).setText("Fixture plan");
            View confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue(confirm.performClick());
            assertFalse(confirm.isEnabled());
            CountDownLatch committed = new CountDownLatch(1);
            SearchPlanStore.execute(committed::countDown);
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertEquals(1, SearchPlanStore.list().size());
            assertManualOnly(SearchPlanStore.list().get(0).getAsString("query"));
        }
    }

    @Test public void clearFiltersRestoresAccountExclusionsWithoutChangingKeywords() throws Exception {
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            awaitBlacklist(activity);
            ViewGroup group = activity.findViewById(R.id.tag_group);
            SearchUiTestSupport.selectStatus(chip(group, MANUAL_ID), 1);
            SearchUiTestSupport.removeTag(chip(group, AUTOMATIC_ID));
            activity.setQuery("fixture", false);
            com.theskyc.nreader.api.components.Ranges ranges = ReflectionHelpers.getField(activity, "ranges");
            ranges.setFromPage(20);
            activity.findViewById(R.id.search_clear_filters).performClick();
            AlertDialog dialog = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            awaitBlacklist(activity);
            assertEquals("fixture", ((com.google.android.material.search.SearchBar)
                activity.findViewById(R.id.toolbar)).getText().toString());
            assertEquals("fixture", ((com.google.android.material.search.SearchView)
                activity.findViewById(R.id.search_view)).getEditText().getText().toString());
            assertTrue(((com.theskyc.nreader.api.components.Ranges)
                ReflectionHelpers.getField(activity, "ranges")).isDefault());
            assertEquals(TagStatus.AVOIDED, chip(group, MANUAL_ID).getTag().getStatus());
            assertEquals(TagStatus.AVOIDED, chip(group, AUTOMATIC_ID).getTag().getStatus());
        }
    }

    @Test public void blacklistRefreshDoesNotReapplyClearedOrRemovedSelections() throws Exception {
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            ViewGroup group = activity.findViewById(R.id.tag_group);
            awaitBlacklist(activity);
            ChipTag neutral = chip(group, MANUAL_ID);
            ChipTag removed = chip(group, AUTOMATIC_ID);
            assertNotNull(neutral);
            assertNotNull(removed);
            SearchUiTestSupport.selectStatus(neutral, 0);
            assertEquals(TagStatus.DEFAULT, neutral.getTag().getStatus());
            SearchUiTestSupport.removeTag(removed);
            controller.recreate();
            activity = controller.get();
            group = activity.findViewById(R.id.tag_group);
            awaitBlacklist(activity);
            neutral = chip(group, MANUAL_ID);
            assertNotNull(neutral);
            assertEquals(TagStatus.DEFAULT, neutral.getTag().getStatus());
            assertTrue(chip(group, AUTOMATIC_ID) == null);
            replaceBlacklist(Arrays.asList(
                new Tag("manual selection", 100000, MANUAL_ID, TagType.TAG, TagStatus.DEFAULT),
                new Tag("automatic selection", 1, AUTOMATIC_ID, TagType.TAG, TagStatus.DEFAULT)));
            awaitBlacklist(activity);
            assertEquals(TagStatus.DEFAULT, neutral.getTag().getStatus());
            assertTrue(chip(group, AUTOMATIC_ID) == null);
        }
    }

    private static void awaitBlacklist(SearchActivity activity) throws InterruptedException {
        long expected = Login.currentOnlineTagsRevision();
        for (int attempt = 0; attempt < 100; attempt++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            long applied = ReflectionHelpers.getField(activity, "onlineChipsRevision");
            if (applied == expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Search form did not apply the blacklist revision");
    }

    private Intent submit() throws Exception {
        try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
            SearchActivity activity = controller.get();
            ViewGroup group = activity.findViewById(R.id.tag_group);
            ChipTag manual = null;
            ChipTag automatic = null;
            for (int attempt = 0; attempt < 100 && (manual == null || automatic == null); attempt++) {
                Thread.sleep(20);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                manual = chip(group, MANUAL_ID);
                automatic = chip(group, AUTOMATIC_ID);
            }
            assertNotNull(manual);
            assertNotNull(automatic);
            assertEquals(TagStatus.AVOIDED, automatic.getTag().getStatus());
            assertEquals(TagStatus.AVOIDED, manual.getTag().getStatus());
            SearchUiTestSupport.selectStatus(manual, 1);
            assertEquals(TagStatus.ACCEPTED, manual.getTag().getStatus());
            activity.setQuery("fixture", true);
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            ArrayList<Tag> portable = intent.getParcelableArrayListExtra(app.getPackageName() + ".TAGS");
            assertNotNull(portable);
            assertEquals(1, portable.size());
            assertEquals(MANUAL_ID, portable.get(0).getId());
            Parcel parcel = Parcel.obtain();
            try {
                intent.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                return Intent.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }

    private void replaceBlacklist(List<Tag> tags) {
        String identity = AuthStore.activeIdentity(app);
        assertTrue(Login.replaceOnlineTags(app, identity, Login.beginOnlineTagsLoad(app, identity), tags));
    }

    private static ChipTag chip(ViewGroup group, int id) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ChipTag && ((ChipTag) child).getTag().getId() == id) return (ChipTag) child;
        }
        return null;
    }

    private static InspectorV3 awaitSearch(MainActivity activity) throws InterruptedException {
        InspectorV3 inspector = ReflectionHelpers.getField(activity, "inspector");
        inspector.join(5000);
        assertFalse(inspector.isAlive());
        return inspector;
    }

    private static void chooseFallback(MainActivity activity) {
        Snackbar snackbar = ReflectionHelpers.getField(activity, "snackbar");
        assertNotNull(snackbar);
        assertTrue(snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action).performClick());
    }

    private static void assertManualOnly(String query) {
        assertTrue(query.contains("fixture"));
        assertTrue(query.contains("tag:\"manual selection\""));
        assertFalse(query.contains("automatic selection"));
    }
}
