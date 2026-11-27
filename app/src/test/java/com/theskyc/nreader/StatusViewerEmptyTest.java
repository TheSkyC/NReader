package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.database.MatrixCursor;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.theskyc.nreader.adapters.StatusViewerAdapter;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.components.status.Status;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.ui.main.PlaceholderFragment;
import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.google.android.material.tabs.TabLayout;

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
    public void collapsedStatusResultsRemainWithinTheViewportAfterSearch() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            Database.setDatabase(database.getWritableDatabase());
            StatusManager.replaceAll(Collections.singletonList(new Status(0xff336699, "Reading")));
            try (ActivityController<StatusViewerActivity> controller =
                     Robolectric.buildActivity(StatusViewerActivity.class).setup()) {
                StatusViewerActivity activity = controller.get();
                SearchView search = activity.findViewById(R.id.search_view);
                search.show();
                search.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View root = activity.findViewById(android.R.id.content);
                View results = activity.findViewById(R.id.search_results);
                for (int[] size : new int[][]{{864, 1536}, {1536, 864}}) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, size[0], size[1]);
                    int bottom = results.getHeight();
                    for (View view = results; view != root; view = (View) view.getParent()) bottom += view.getTop();
                    assertTrue("status results extend below the viewport: " + bottom, bottom <= root.getHeight());
                }
            }
        }
    }

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
                assertTrue(isInside(activity.findViewById(R.id.no_statuses), searchView));

                searchView.getEditText().setText("reading");
                assertEquals("reading", searchBar.getText().toString());
                controller.recreate();

                SearchBar restoredBar = controller.get().findViewById(R.id.toolbar);
                SearchView restoredView = controller.get().findViewById(R.id.search_view);
                assertTrue(restoredView.isShowing());
                assertTrue(isInside(controller.get().findViewById(R.id.no_statuses), restoredView));
                assertEquals("reading", restoredBar.getText().toString());
                assertEquals("reading", restoredView.getEditText().getText().toString());
                restoredView.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(restoredView.isShowing());
                assertFalse(isInside(controller.get().findViewById(R.id.no_statuses), restoredView));
                assertEquals("reading", restoredBar.getText().toString());
            }
        }
    }

    @Test
    public void searchKeepsStatusTabsAndPagerUsable() {
        Application app = RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            Database.setDatabase(database.getWritableDatabase());
            StatusManager.replaceAll(java.util.Arrays.asList(
                new Status(0xff336699, "Reading"), new Status(0xff993366, "Finished")));
            try (ActivityController<StatusViewerActivity> controller =
                     Robolectric.buildActivity(StatusViewerActivity.class).setup()) {
                StatusViewerActivity activity = controller.get();
                SearchView search = activity.findViewById(R.id.search_view);
                TabLayout tabs = activity.findViewById(R.id.tabs);
                ViewPager2 pager = activity.findViewById(R.id.view_pager);
                search.show();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(isInside(tabs, search));
                assertTrue(isInside(pager, search));
                tabs.getTabAt(1).select();
                assertEquals(1, pager.getCurrentItem());
                search.hide();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(isInside(pager, search));
                assertEquals(1, pager.getCurrentItem());
            }
        }
    }

    private static boolean isInside(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }
}
