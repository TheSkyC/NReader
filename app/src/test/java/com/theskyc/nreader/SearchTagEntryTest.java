package com.theskyc.nreader;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Filterable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.SearchView;

import com.theskyc.nreader.api.components.Ranges;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagLanguage;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    @Test public void recreationPreservesFiltersAndSearchInput() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putInt(app.getString(R.string.preference_key_favorite_limit), 1).commit();
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("fixture", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                ViewGroup group = activity.findViewById(R.id.tag_group);
                await(() -> group.getChildCount() > 0);
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar));
                activity.onCreateOptionsMenu(popup.getMenu());
                activity.onOptionsItemSelected(popup.getMenu().findItem(R.id.view_groups));
                activity.findViewById(R.id.tag_header).performClick();
                SearchUiTestSupport.selectStatus((ChipTag) group.getChildAt(0), 2);
                ((SearchView) activity.findViewById(R.id.search)).setQuery("keywords", false);
                Ranges ranges = ReflectionHelpers.getField(activity, "ranges");
                ranges.setFromPage(20);
                ranges.setToDate(2);
                ranges.setToDateUnit(Ranges.TimeUnit.WEEK);

                controller.recreate();
                SearchActivity restored = controller.get();
                ViewGroup restoredGroup = restored.findViewById(R.id.tag_group);
                await(() -> restoredGroup.getChildCount() > 0);
                assertEquals(View.VISIBLE, restored.findViewById(R.id.groups).getVisibility());
                assertEquals(View.VISIBLE, restoredGroup.getVisibility());
                assertEquals("keywords", ((SearchView) restored.findViewById(R.id.search)).getQuery().toString());
                assertEquals(TagStatus.AVOIDED, ((ChipTag) restoredGroup.getChildAt(0)).getTag().getStatus());
                Ranges restoredRanges = ReflectionHelpers.getField(restored, "ranges");
                assertEquals("pages:>=20 uploaded:<=2w", restoredRanges.toQuery());
                View pageRange = restored.findViewById(R.id.page_range);
                assertEquals("20", ((android.widget.Button) pageRange.findViewById(R.id.fromButton)).getText().toString());
                ViewGroup summary = restored.findViewById(R.id.search_selected_filters);
                assertEquals(3, summary.getChildCount());
                assertTrue(((Chip) summary.getChildAt(0)).getText().toString().contains("20"));
                assertTrue(((Chip) summary.getChildAt(1)).getText().toString()
                    .startsWith(restored.getString(R.string.search_range_to_time)));
                assertTrue(((Chip) summary.getChildAt(0)).performCloseIconClick());
                assertEquals(Ranges.UNDEFINED, restoredRanges.getFromPage());
                assertEquals("uploaded:<=2w", restoredRanges.toQuery());
                assertEquals(restored.getString(R.string.search_range_any),
                    ((android.widget.Button) pageRange.findViewById(R.id.fromButton)).getText().toString());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void filterModeProvidesSearchForAnEmptyKeyword() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                await(() -> activity.findViewById(R.id.tag_add).isEnabled());
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar));
                activity.onCreateOptionsMenu(popup.getMenu());
                activity.onOptionsItemSelected(popup.getMenu().findItem(R.id.view_groups));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.groups).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.recycler).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.search_actions).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.tag_group).getVisibility());
                activity.findViewById(R.id.tag_header).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.tag_group).getVisibility());
                View pageRange = activity.findViewById(R.id.page_range);
                assertEquals(activity.getString(R.string.search_range_any),
                    ((android.widget.Button) pageRange.findViewById(R.id.fromButton)).getText().toString());
                assertEquals(activity.getString(R.string.search_range_any),
                    ((android.widget.Button) pageRange.findViewById(R.id.toButton)).getText().toString());
                activity.findViewById(R.id.search_submit).performClick();
                Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(sent);
                assertEquals("", sent.getStringExtra(activity.getPackageName() + ".QUERY"));
                assertTrue(sent.getBooleanExtra(activity.getPackageName() + ".ADVANCED", false));
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void editingPageBoundsPreservesConflictingValuesUntilCorrected() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                await(() -> activity.findViewById(R.id.tag_add).isEnabled());
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar));
                activity.onCreateOptionsMenu(popup.getMenu());
                activity.onOptionsItemSelected(popup.getMenu().findItem(R.id.view_groups));
                Ranges ranges = ReflectionHelpers.getField(activity, "ranges");
                ranges.setToPage(10);
                View pageRange = activity.findViewById(R.id.page_range);
                pageRange.findViewById(R.id.fromButton).performClick();
                AlertDialog value = (AlertDialog) ShadowDialog.getLatestDialog();
                ((android.widget.EditText) value.findViewById(R.id.edit_page)).setText("20");
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                value.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(20, ranges.getFromPage());
                assertEquals(10, ranges.getToPage());
                assertEquals(View.VISIBLE, pageRange.findViewById(R.id.range_error).getVisibility());
                activity.findViewById(R.id.search_submit).performClick();
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                AlertDialog error = (AlertDialog) ShadowDialog.getLatestDialog();
                error.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ViewGroup summary = activity.findViewById(R.id.search_selected_filters);
                assertTrue(((Chip) summary.getChildAt(0)).performCloseIconClick());
                assertEquals(10, ranges.getToPage());
                assertEquals(View.GONE, pageRange.findViewById(R.id.range_error).getVisibility());
                activity.findViewById(R.id.search_submit).performClick();
                assertNotNull(Shadows.shadowOf(activity).getNextStartedActivity());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void conflictingUploadBoundsBlockSubmissionUntilCorrected() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                await(() -> activity.findViewById(R.id.tag_add).isEnabled());
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar));
                activity.onCreateOptionsMenu(popup.getMenu());
                activity.onOptionsItemSelected(popup.getMenu().findItem(R.id.view_groups));
                Ranges ranges = ReflectionHelpers.getField(activity, "ranges");
                ranges.setFromDate(10);
                ranges.setFromDateUnit(Ranges.TimeUnit.DAY);
                ranges.setToDate(2);
                ranges.setToDateUnit(Ranges.TimeUnit.DAY);
                ReflectionHelpers.callInstanceMethod(activity, "updateFilterSummary");
                View rangeError = activity.findViewById(R.id.upload_range).findViewById(R.id.range_error);
                assertEquals(View.VISIBLE, rangeError.getVisibility());
                activity.findViewById(R.id.search_submit).performClick();
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                AlertDialog error = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(error.isShowing());
                error.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ranges.setToDate(20);
                ReflectionHelpers.callInstanceMethod(activity, "updateFilterSummary");
                assertEquals(View.GONE, rangeError.getVisibility());
                activity.findViewById(R.id.search_submit).performClick();
                assertNotNull(Shadows.shadowOf(activity).getNextStartedActivity());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void editingUploadTimePreservesExistingValue() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                Ranges ranges = ReflectionHelpers.getField(activity, "ranges");
                ranges.setFromDate(20);
                ranges.setFromDateUnit(Ranges.TimeUnit.DAY);
                activity.findViewById(R.id.upload_range).findViewById(R.id.fromButton).performClick();
                AlertDialog units = (AlertDialog) ShadowDialog.getLatestDialog();
                units.getListView().performItemClick(units.getListView().getChildAt(1), 1, 1);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                AlertDialog value = (AlertDialog) ShadowDialog.getLatestDialog();
                assertEquals("20", ((android.widget.EditText) value.findViewById(R.id.edit_page)).getText().toString());
                value.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertEquals(20, ranges.getFromDate());
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void keyboardSubmissionAppliesVisibleFilters() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            helper.getWritableDatabase();
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                SearchActivity activity = controller.get();
                await(() -> activity.findViewById(R.id.tag_add).isEnabled());
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar));
                activity.onCreateOptionsMenu(popup.getMenu());
                activity.onOptionsItemSelected(popup.getMenu().findItem(R.id.view_groups));
                ((SearchView) activity.findViewById(R.id.search)).setQuery("keywords", true);
                Intent sent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(sent);
                assertTrue(sent.getBooleanExtra(activity.getPackageName() + ".ADVANCED", false));
            }
        } finally {
            Queries.setDb(null);
        }
    }

    @Test public void visibleTagFollowsLanguageWithoutChangingSearchIdentity() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        app.getSharedPreferences("Settings", 0).edit()
            .putInt(app.getString(R.string.preference_key_favorite_limit), 1).commit();
        TagLanguage.select(app, "");
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("original name", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":501001,\"name\":\"Translated label\"}]}";
            assertTrue(TagTranslationCatalog.apply(database,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                ViewGroup group = controller.get().findViewById(R.id.tag_group);
                await(() -> group.getChildCount() > 0);
                ChipTag chip = (ChipTag) group.getChildAt(0);
                SearchUiTestSupport.selectStatus(chip, 1);
                assertEquals(controller.get().getString(R.string.search_filters_summary, 1, 0),
                    ((android.widget.TextView) controller.get().findViewById(R.id.search_filter_summary)).getText().toString());
                TagLanguage.select(app, "ja");
                await(() -> chip.getText().toString().contains("Translated label"));
                assertEquals("original name", chip.getTag().getName());
                assertEquals(501001, chip.getTag().getId());
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                ViewGroup selected = controller.get().findViewById(R.id.search_selected_filters);
                assertEquals(1, selected.getChildCount());
                assertTrue(((Chip) selected.getChildAt(0)).getText().toString().contains("Translated label"));
                TagLanguage.select(app, "");
                await(() -> chip.getText().toString().equals("original name"));
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                assertTrue(((Chip) selected.getChildAt(0)).performCloseIconClick());
                assertEquals(TagStatus.DEFAULT, chip.getTag().getStatus());
                assertEquals(0, selected.getChildCount());
            }
        } finally {
            TagLanguage.select(app, "");
            Queries.setDb(null);
        }
    }

    @Test public void suggestionsFollowSelectedTagLanguageWhileDialogIsOpen() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        TagLanguage.select(app, "");
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("site name", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":501001,\"name\":\"Japanese label\",\"aliases\":[\"unique alias\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                AlertDialog dialog = openTagDialog(controller.get());
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("unique alias", true);
                AtomicBoolean filtered = new AtomicBoolean();
                ((Filterable) entry.getAdapter()).getFilter().filter("unique alias", count -> filtered.set(true));
                await(filtered::get);
                assertEquals(0, entry.getAdapter().getCount());
                TagLanguage.select(app, "ja");
                await(() -> entry.getAdapter().getCount() == 1);
                TagLanguage.select(app, "");
                await(() -> entry.getAdapter().getCount() == 0);
                assertEquals("unique alias", entry.getText().toString());
            }
        } finally {
            TagLanguage.select(app, "");
            Queries.setDb(null);
        }
    }

    @Test public void suggestionsRefreshAfterTranslationKeyRebuild() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        initializeSearch(app);
        try (DatabaseHelper helper = new DatabaseHelper(app)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Queries.TagTable.insert(new Tag("refresh fixture", 100, 501001, TagType.TAG, TagStatus.DEFAULT));
            String pack = "{\"schemaVersion\":2,\"locale\":\"en\",\"revision\":1,"
                + "\"entries\":[{\"id\":501001,\"name\":\"Translated label\",\"aliases\":[\"refresh alias\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database,
                new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)), app.getCacheDir(), () -> false));
            database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage, lastRowId) VALUES (1, 0, NULL)");
            try (ActivityController<SearchActivity> controller = Robolectric.buildActivity(SearchActivity.class).setup()) {
                AlertDialog dialog = openTagDialog(controller.get());
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("refresh alias", true);
                AtomicBoolean filtered = new AtomicBoolean();
                ((Filterable) entry.getAdapter()).getFilter().filter("refresh alias", count -> filtered.set(true));
                await(filtered::get);
                assertEquals(0, entry.getAdapter().getCount());
                while (!TagTranslationCatalog.rebuildUnicodeKeyBatch(database, () -> false)) { }
                await(() -> entry.getAdapter().getCount() == 1);
                assertEquals("refresh alias", entry.getText().toString());
            }
        } finally {
            Queries.setDb(null);
        }
    }

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
                ViewGroup group = activity.findViewById(R.id.tag_group);
                await(() -> group.getChildCount() > 0);
                ChipTag chip = (ChipTag) group.getChildAt(0);
                SearchUiTestSupport.selectStatus(chip, 1);
                AlertDialog dialog = openTagDialog(activity);
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
                assertEquals(1, group.getChildCount());
                assertEquals(501001, chip.getTag().getId());
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                assertEquals("Canonical Rename", chip.getTag().getName());
                await(() -> chip.getText().toString().contains("Translated label"));
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
                ViewGroup group = activity.findViewById(R.id.tag_group);
                await(() -> group.getChildCount() > 0);
                ChipTag first = (ChipTag) group.getChildAt(0);
                assertEquals(501001, first.getTag().getId());
                SearchUiTestSupport.selectStatus(first, 1);
                AlertDialog dialog = openTagDialog(activity);
                AppCompatAutoCompleteTextView entry = dialog.findViewById(R.id.edit);
                entry.setText("second alias", true);
                await(() -> entry.getAdapter().getCount() == 1);
                entry.showDropDown();
                entry.setListSelection(0);
                entry.performCompletion();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

                await(() -> !dialog.isShowing());
                assertEquals(2, group.getChildCount());
                ChipTag second = (ChipTag) group.getChildAt(1);
                assertEquals(501002, second.getTag().getId());
                assertEquals(TagStatus.ACCEPTED, second.getTag().getStatus());
                SearchUiTestSupport.selectStatus(second, 1);
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
                assertEquals(TagStatus.ACCEPTED, chip.getTag().getStatus());
                SearchUiTestSupport.selectStatus(chip, 2);
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
                assertEquals(0, group.getChildCount());
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
                assertEquals(2, group.getChildCount());
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
            assertEquals(0, group.getChildCount());
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
                assertEquals(0, group.getChildCount());

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
        View add = activity.findViewById(R.id.tag_add);
        await(add::isEnabled);
        assertTrue(add.performClick());
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
