// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.Menu;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.theskyc.nreader.api.SimpleGallery;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.settings.Global;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class HistoryClearTest {
    @Test
    public void clearRequiresConfirmationAndDisappearsWhenEmpty() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("Entries.db");
        try (DatabaseHelper database = new DatabaseHelper(app)) {
            database.getWritableDatabase();
            Global.initFromShared(app);
            SimpleGallery gallery = SimpleGallery.fromV2ListItem(app, new JSONObject(
                "{\"id\":123,\"media_id\":\"456\",\"english_title\":\"Fixture\",\"thumbnail\":\"thumb.jpg\"}"));
            Queries.HistoryTable.addGallery(gallery);

            try (ActivityController<HistoryActivity> controller = Robolectric.buildActivity(HistoryActivity.class).setup()) {
                HistoryActivity activity = controller.get();
                Menu menu = ((Toolbar) activity.findViewById(R.id.toolbar)).getMenu();
                activity.onPrepareOptionsMenu(menu);
                assertTrue(menu.findItem(R.id.cancelAll).isVisible());

                activity.onOptionsItemSelected(menu.findItem(R.id.cancelAll));
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertNotNull(dialog);
                assertEquals(1, Queries.HistoryTable.getHistory().size());
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
                assertEquals(1, Queries.HistoryTable.getHistory().size());

                activity.onOptionsItemSelected(menu.findItem(R.id.cancelAll));
                dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, Queries.HistoryTable.getHistory().size());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.history_empty).getVisibility());
                activity.onPrepareOptionsMenu(menu);
                assertFalse(menu.findItem(R.id.cancelAll).isVisible());
            }
        }
    }
}
