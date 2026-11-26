package com.theskyc.nreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.search.SearchView;
import com.theskyc.nreader.adapters.LocalAdapter;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalSelectionToolbarTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private DatabaseHelper database;

    @Before public void openDatabase() {
        database = new DatabaseHelper(org.robolectric.RuntimeEnvironment.getApplication());
        database.getWritableDatabase();
    }

    @After public void closeDatabase() {
        database.close();
        Queries.setDb(null);
    }

    @Test public void enteringSelectionClosesSearchAndExpandsToolbar() throws Exception {
        Global.initFromShared(org.robolectric.RuntimeEnvironment.getApplication());
        try (ActivityController<LocalActivity> controller =
                 Robolectric.buildActivity(LocalActivity.class).setup()) {
            LocalActivity activity = controller.get();
            LocalGallery gallery = new LocalGallery(temporaryFolder.newFolder("selection"), true);
            LocalAdapter adapter = new LocalAdapter(activity, new ArrayList<>(Collections.singletonList(gallery)));
            activity.setAdapter(adapter);
            SearchView search = activity.findViewById(R.id.search_view);
            AppBarLayout appBar = activity.findViewById(R.id.local_app_bar);
            search.show();
            assertTrue(search.isShowing());
            appBar.setExpanded(false, false);

            adapter.selectAll();

            assertFalse(search.isShowing());
            int pendingAction = ReflectionHelpers.callInstanceMethod(appBar, "getPendingAction");
            assertTrue((pendingAction & 1) != 0);
            adapter.deselectAll();
            adapter.removeObserver();
        }
    }
}
