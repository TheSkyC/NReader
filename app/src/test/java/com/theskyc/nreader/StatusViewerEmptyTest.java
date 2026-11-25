package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.database.MatrixCursor;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.theskyc.nreader.adapters.StatusViewerAdapter;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.ui.main.PlaceholderFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
                TextView message = activity.findViewById(R.id.no_statuses);
                assertEquals(View.VISIBLE, message.getVisibility());
                assertEquals(activity.getString(R.string.status_viewer_no_statuses), message.getText().toString());
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
}
