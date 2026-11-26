package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;

import com.google.android.material.search.SearchBar;
import com.google.android.material.search.SearchView;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalSearchMaterial3Test {
    @Test
    public void searchRestoresExpandedQueryAndRetainsCollapsedLabel() {
        Application app = org.robolectric.RuntimeEnvironment.getApplication();
        Global.initFromShared(app);
        try (ActivityController<LocalActivity> controller =
                 Robolectric.buildActivity(LocalActivity.class).setup()) {
            LocalActivity activity = controller.get();
            SearchBar searchBar = activity.findViewById(R.id.toolbar);
            SearchView searchView = activity.findViewById(R.id.search_view);
            assertFalse(searchView.isShowing());

            searchBar.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(searchView.isShowing());
            assertTrue(isInside(activity.findViewById(R.id.recycler), searchView));
            searchView.setText("local fixture");

            controller.recreate();
            LocalActivity restored = controller.get();
            SearchBar restoredBar = restored.findViewById(R.id.toolbar);
            SearchView restoredView = restored.findViewById(R.id.search_view);
            assertTrue(restoredView.isShowing());
            assertTrue(isInside(restored.findViewById(R.id.recycler), restoredView));
            assertEquals("local fixture", restored.getQuery());
            assertEquals("local fixture", restoredView.getEditText().getText().toString());

            restored.getOnBackPressedDispatcher().onBackPressed();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(restoredView.isShowing());
            assertFalse(restored.isFinishing());
            assertFalse(isInside(restored.findViewById(R.id.recycler), restoredView));
            assertEquals("local fixture", restoredBar.getText().toString());
        }
    }

    private boolean isInside(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }
}
