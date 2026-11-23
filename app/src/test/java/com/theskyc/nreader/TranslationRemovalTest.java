package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TranslationRemovalTest {
    @Test public void staleListSelectionKeepsNewerPackAndExplainsWhy() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        SQLiteDatabase previous = Database.getDatabase();
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
            Map<String, TagTranslationCatalog.PackState> displayed = TagTranslationCatalog.installedPackStates(database);
            database.execSQL("UPDATE TagTranslationState SET revision = 2 WHERE locale = 'ja'");
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity activity = controller.get();
                ReflectionHelpers.callInstanceMethod(activity, "showTranslationPacks",
                    ReflectionHelpers.ClassParameter.from(Map.class, displayed));
                AlertDialog list = (AlertDialog) ShadowDialog.getLatestDialog();
                list.getListView().performItemClick(list.getListView().getChildAt(0), 0, 0);
                AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String expected = activity.getString(R.string.tag_translation_remove_changed);
                while (!expected.contentEquals(ShadowToast.getTextOfLatestToast() == null
                    ? "" : ShadowToast.getTextOfLatestToast()) && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals(expected, ShadowToast.getTextOfLatestToast());
                try (Cursor cursor = database.rawQuery(
                    "SELECT 1 FROM TagTranslationState WHERE locale='ja' AND revision=2", null)) {
                    assertTrue(cursor.moveToFirst());
                }
            }
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
        }
    }
}
