package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.database.MatrixCursor;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.theskyc.nreader.adapters.StatusViewerAdapter;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.components.status.Status;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.ui.main.PlaceholderFragment;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class StatusViewerEmptyTest {
    @Test
    public void noStatusesShowsGuidanceWithoutTabs() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            Database.setDatabase(database.getWritableDatabase());
            StatusManager.replaceAll(Collections.emptyList());
            try (ActivityController<StatusViewerActivity> controller =
                     Robolectric.buildActivity(StatusViewerActivity.class).setup()) {
                StatusViewerActivity activity = controller.get();
                assertEquals(View.GONE, activity.findViewById(R.id.tabs).getVisibility());
                View emptyState = activity.findViewById(R.id.no_statuses);
                assertEquals(View.VISIBLE, emptyState.getVisibility());
                TextView message = activity.findViewById(R.id.no_statuses_message);
                assertEquals(activity.getString(R.string.status_viewer_no_statuses), message.getText().toString());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.manage_statuses_action).getVisibility());

                activity.findViewById(R.id.manage_statuses_action).performClick();
                Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
                assertEquals(StatusManagerActivity.class.getName(), intent.getComponent().getClassName());

                controller.pause().stop();
                StatusManager.replaceAll(Collections.singletonList(new Status(0xff336699, "Reading")));
                controller.restart().resume();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                StatusViewerActivity resumed = controller.get();
                assertEquals(View.GONE, resumed.findViewById(R.id.no_statuses).getVisibility());
                assertEquals(View.VISIBLE, resumed.findViewById(R.id.tabs).getVisibility());
            }
        }
    }

    @Test
    public void emptyMessageTracksSearchAndResults() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            Database.setDatabase(database.getWritableDatabase());
            try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
                AppCompatActivity activity = controller.get();
                activity.setTheme(R.style.AppTheme_Material3);
                controller.setup();
                PlaceholderFragment fragment = PlaceholderFragment.newInstance("Reading");
                activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
                TextView message = fragment.requireView().findViewById(R.id.empty_message);
                assertEquals(View.VISIBLE, message.getVisibility());
                assertEquals(activity.getString(R.string.status_viewer_empty), message.getText().toString());

                fragment.changeQuery("missing");
                assertEquals(activity.getString(R.string.status_viewer_no_matches), message.getText().toString());
                StatusViewerAdapter adapter = ReflectionHelpers.getField(fragment, "adapter");
                MatrixCursor results = new MatrixCursor(new String[]{"id"});
                results.addRow(new Object[]{1});
                adapter.setGalleries(results);
                assertEquals(View.GONE, message.getVisibility());

                fragment.changeQuery("");
                assertEquals(View.VISIBLE, message.getVisibility());
                assertEquals(activity.getString(R.string.status_viewer_empty), message.getText().toString());
            }
        }
    }

    @Test
    public void materialSearchRestoresQueryAndExpandedState() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            Database.setDatabase(database.getWritableDatabase());
            StatusManager.replaceAll(Collections.emptyList());
            try (ActivityController<StatusViewerActivity> controller =
                     Robolectric.buildActivity(StatusViewerActivity.class).setup()) {
                StatusViewerActivity activity = controller.get();
                SearchBar searchBar = activity.findViewById(R.id.toolbar);
                SearchView searchView = activity.findViewById(R.id.search_view);
                assertFalse(searchView.isShowing());

                searchBar.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(searchView.isShowing());

                searchView.getEditText().setText("reading");
                assertEquals("reading", searchBar.getText().toString());
                controller.recreate();

                SearchBar restoredBar = controller.get().findViewById(R.id.toolbar);
                SearchView restoredView = controller.get().findViewById(R.id.search_view);
                assertTrue(restoredView.isShowing());
                assertEquals("reading", restoredBar.getText().toString());
                assertEquals("reading", restoredView.getEditText().getText().toString());
                restoredView.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(restoredView.isShowing());
                assertEquals("reading", restoredBar.getText().toString());
            }
        }
    }
}
