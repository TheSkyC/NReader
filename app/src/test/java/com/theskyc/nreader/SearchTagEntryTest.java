package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.SearchView;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;
import com.google.android.material.chip.Chip;

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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class SearchTagEntryTest {
    @Test public void translatedSuggestionSubmitsOriginalSiteTag() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putInt(app.getString(R.string.preference_key_favorite_limit), 1).commit();
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("Mixed Case", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
                + "\"entries\":[{\"id\":501001,\"name\":\"Translated label\",\"aliases\":[\"alias term\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                AlertDialog dialog = openTagDialog(activity);
                ViewGroup group = activity.findViewById(R.id.tag_group);
                ChipTag chip = (ChipTag) group.getChildAt(0);
                chip.performClick();
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("alias term", true);
                await(() -> entry.getAdapter().getCount() == 1);
                entry.showDropDown();
                entry.setListSelection(0);
                entry.performCompletion();
                assertEquals("Mixed Case", entry.getText().toString());
                database.execSQL("UPDATE Tags SET name = 'Canonical Rename' WHERE idTag = 501001");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

                await(() -> !dialog.isShowing());
                assertEquals(2, group.getChildCount());
                assertEquals(501001, chip.getTag().getId());
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                assertEquals("Canonical Rename", chip.getText().toString());
                ((SearchView) activity.findViewById(R.id.search)).setQuery("fixture", true);
                Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(sent);
                ArrayList<Tag> tags = sent.getParcelableArrayListExtra(activity.getPackageName() + ".TAGS");
                assertNotNull(tags);
                assertEquals(1, tags.size());
                assertEquals(501001, tags.get(0).getId());
                assertEquals("tag:\"Canonical Rename\"", tags.get(0).toQueryTag());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void translatedSuggestionKeepsDistinctSiteIdWithTheSameName() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putInt(app.getString(R.string.preference_key_favorite_limit), 1).commit();
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("Same name", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            Queries.TagTable.insert(new Tag("Same name", 50, 501002, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
                + "\"entries\":[{\"id\":501002,\"name\":\"Second translation\",\"aliases\":[\"second alias\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                AlertDialog dialog = openTagDialog(activity);
                ViewGroup group = activity.findViewById(R.id.tag_group);
                ChipTag first = (ChipTag) group.getChildAt(0);
                assertEquals(501001, first.getTag().getId());
                assertTrue(first.performClick());
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("second alias", true);
                await(() -> entry.getAdapter().getCount() == 1);
                entry.showDropDown();
                entry.setListSelection(0);
                entry.performCompletion();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

                await(() -> !dialog.isShowing());
                assertEquals(3, group.getChildCount());
                ChipTag second = (ChipTag) group.getChildAt(1);
                assertEquals(501002, second.getTag().getId());
                assertEquals(TagStatus.DEFAULT, second.getTag().getStatus());
                assertTrue(second.performClick());
                ((SearchView) activity.findViewById(R.id.search)).setQuery("fixture", true);
                Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(sent);
                ArrayList<Tag> tags = sent.getParcelableArrayListExtra(activity.getPackageName() + ".TAGS");
                assertNotNull(tags);
                assertEquals(2, tags.size());
                assertEquals(501001, tags.get(0).getId());
                assertEquals(501002, tags.get(1).getId());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void typedLookupKeepsUiResponsiveAndUsesExactSpelling() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("mixed case", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            Queries.TagTable.insert(new Tag("Mixed Case", 100, 501002, TagType.TAG, TagStatus.DEFAULT));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                AlertDialog dialog = openTagDialog(activity);
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("  Mixed Case  ", false);
                ExecutorService loader = ReflectionHelpers.getField(activity, "tagLoader");
                CountDownLatch releaseLoader = new CountDownLatch(1);
                Future<?> paused = loader.submit(() -> {
                    releaseLoader.await();
                    return null;
                });
                try {
                    entry.onEditorAction(EditorInfo.IME_ACTION_SEND);
                    assertTrue(dialog.isShowing());
                    assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                    ((SearchView) activity.findViewById(R.id.search)).setQuery("fixture", true);
                    assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                } finally {
                    releaseLoader.countDown();
                }
                paused.get(6, TimeUnit.SECONDS);
                await(() -> !dialog.isShowing());

                ViewGroup group = activity.findViewById(R.id.tag_group);
                ChipTag chip = (ChipTag) group.getChildAt(0);
                assertEquals(501002, chip.getTag().getId());
                assertEquals(TagStatus.DEFAULT, chip.getTag().getStatus());
                chip.performClick();
                chip.performClick();
                assertEquals(TagStatus.AVOIDED, chip.getTag().getStatus());
                ((SearchView) activity.findViewById(R.id.search)).setQuery("fixture", true);
                Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(sent);
                ArrayList<Tag> tags = sent.getParcelableArrayListExtra(activity.getPackageName() + ".TAGS");
                assertNotNull(tags);
                assertEquals(1, tags.size());
                assertEquals("-tag:\"Mixed Case\"", tags.get(0).toQueryTag());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void cancellingAfterLookupPostsResultDoesNotAddTag() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                AlertDialog dialog = openTagDialog(activity);
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("custom label", false);
                ExecutorService loader = ReflectionHelpers.getField(activity, "tagLoader");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                loader.submit(() -> {}).get(6, TimeUnit.SECONDS);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse(dialog.isShowing());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                ViewGroup group = activity.findViewById(R.id.tag_group);
                assertEquals(1, group.getChildCount());
                AlertDialog next = openTagDialog(activity);
                AppCompatAutoCompleteTextView newEntry = next.findViewById(R.id.edit);
                newEntry.setText("Custom Label", false);
                next.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                await(() -> !next.isShowing());
                ChipTag chip = (ChipTag) group.getChildAt(0);
                assertEquals("custom label", chip.getTag().getName());
                assertTrue(chip.getTag().getId() <= 0);
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());

                AlertDialog third = openTagDialog(activity);
                AppCompatAutoCompleteTextView thirdEntry = third.findViewById(R.id.edit);
                thirdEntry.setText("another custom label", false);
                third.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                await(() -> !third.isShowing());
                assertEquals(3, group.getChildCount());
                assertEquals("another custom label", ((ChipTag) group.getChildAt(1)).getTag().getName());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void destroyingSearchClosesDialogAndDiscardsPostedLookup() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            AlertDialog dialog;
            ViewGroup group;
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                group = activity.findViewById(R.id.tag_group);
                dialog = openTagDialog(activity);
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("custom label", false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ExecutorService loader = ReflectionHelpers.getField(activity, "tagLoader");
                loader.submit(() -> {}).get(6, TimeUnit.SECONDS);
                assertTrue(dialog.isShowing());
                assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            }
            assertFalse("Destroyed activity left its tag dialog open", dialog.isShowing());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, group.getChildCount());
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void lookupFailureKeepsTextAndCanBeRetried() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app);
             SQLiteDatabase unavailable = SQLiteDatabase.create(null)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("caf\u00e9", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                AlertDialog dialog = openTagDialog(activity);
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("CAF\u00c9", false);
                Queries.setDb(unavailable);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                await(() -> entry.getError() != null);
                assertTrue(dialog.isShowing());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
                assertEquals("CAF\u00c9", entry.getText().toString());
                ViewGroup group = activity.findViewById(R.id.tag_group);
                assertEquals(1, group.getChildCount());

                Queries.setDb(database);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                await(() -> !dialog.isShowing());
                assertEquals(501001, ((ChipTag) group.getChildAt(0)).getTag().getId());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    private static void initializeSearch(Application app) {
        app.getSharedPreferences("Settings", 0).edit()
            .putInt(app.getString(R.string.preference_key_favorite_limit), 0)
            .putBoolean(app.getString(R.string.preference_key_use_account_tag), false)
            .commit();
        Global.initFromShared(app);
        Login.initLogin(app);
        TagV2.initMinCount(app);
    }

    private static AlertDialog openTagDialog(SearchActivity activity) throws InterruptedException {
        ViewGroup group = activity.findViewById(R.id.tag_group);
        await(() -> group.getChildCount() > 0);
        View last = group.getChildAt(group.getChildCount() - 1);
        assertEquals(activity.getString(R.string.add), ((Chip) last).getText().toString());
        assertTrue(last.performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (ready.getAsBoolean()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertTrue("Search screen did not reach the expected state", ready.getAsBoolean());
    }
}
