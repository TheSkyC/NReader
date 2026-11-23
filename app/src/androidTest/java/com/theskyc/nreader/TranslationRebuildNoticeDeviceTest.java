package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class TranslationRebuildNoticeDeviceTest {
    @Test public void noticeAllowsLanguageManagementAndDisappearsAfterCompletion() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".tagbenchmark"));
        context.deleteDatabase("Entries.db");
        Global.initFromShared(context);
        SQLiteDatabase previous = Database.getDatabase();
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO TagTranslationState (locale, revision) VALUES ('ja', 1)");
            database.execSQL("INSERT INTO TagTranslationKeyRebuild (id, stage) VALUES (1, 0)");
            SettingsActivity activity = (SettingsActivity) instrumentation.startActivitySync(
                new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            UiAutomation automation = instrumentation.getUiAutomation();
            AccessibilityServiceInfo info = automation.getServiceInfo();
            int oldFlags = info.flags;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            try {
                instrumentation.runOnMainSync(activity::manageTranslationPacks);
                waitFor(() -> findText(automation, activity.getString(R.string.tag_translation_rebuild_message), false));
                waitFor(() -> findText(automation, activity.getString(R.string.tag_translation_rebuild_manage), true));
                String packLabel = activity.getString(R.string.tag_translation_pack_item, "ja", 1);
                waitFor(() -> findText(automation, packLabel, false));
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                instrumentation.waitForIdleSync();
                database.execSQL("DELETE FROM TagTranslationKeyRebuild WHERE id = 1");
                instrumentation.runOnMainSync(activity::manageTranslationPacks);
                waitFor(() -> findText(automation, packLabel, false));
                assertTrue(!findText(automation, activity.getString(R.string.tag_translation_rebuild_message), false));
            } finally {
                info.flags = oldFlags;
                automation.setServiceInfo(info);
                instrumentation.runOnMainSync(activity::finishAndRemoveTask);
            }
        } finally {
            Field field = Database.class.getDeclaredField("database");
            field.setAccessible(true);
            field.set(null, previous);
            Queries.setDb(previous);
            context.deleteDatabase("Entries.db");
        }
    }

    private static boolean findText(UiAutomation automation, String text, boolean click) {
        boolean found = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                        try {
                            if (!found && node.isVisibleToUser() && node.getText() != null
                                && node.getText().toString().toLowerCase(Locale.ROOT)
                                    .contains(text.toLowerCase(Locale.ROOT))) {
                                found = !click || node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        } finally {
                            node.recycle();
                        }
                    }
                } finally {
                    root.recycle();
                }
            } finally {
                window.recycle();
            }
        }
        return found;
    }

    private static void waitFor(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for translation management UI");
    }
}
