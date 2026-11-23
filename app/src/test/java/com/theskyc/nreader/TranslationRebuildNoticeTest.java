package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TranslationRebuildNoticeTest {
    @Test public void pendingRebuildExplainsFallbackAndStillAllowsPackManagement() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        SQLiteDatabase previous = Database.getDatabase();
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
            database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage) VALUES (1, 0)");
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity activity = controller.get();
                activity.manageTranslationPacks();
                AlertDialog notice = awaitNewDialog(null);
                assertEquals(activity.getString(R.string.tag_translation_rebuild_message),
                    ((TextView) notice.findViewById(android.R.id.message)).getText().toString());
                notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                AlertDialog list = awaitNewDialog(notice);
                assertNotNull(list.getListView());
                assertEquals(1, list.getListView().getCount());
                list.dismiss();
                database.execSQL("DELETE FROM TagTranslationKeyRebuild WHERE id = 1");
                activity.manageTranslationPacks();
                AlertDialog after = awaitNewDialog(list);
                assertNotNull(after.getListView());
                assertEquals(1, after.getListView().getCount());
            }
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
        }
    }

    private static AlertDialog awaitNewDialog(AlertDialog previous) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AlertDialog dialog;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            if (dialog != null && dialog != previous && dialog.isShowing()) return dialog;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertTrue("Expected a new dialog", false);
        return null;
    }
}
