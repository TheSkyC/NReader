package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class TranslationExportCollisionDeviceTest {
    @Test public void displaysCollisionError() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".tagbenchmark"));
        context.deleteDatabase("Entries.db");
        Global.initFromShared(context);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700006, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700006,\"name\":\"translated\",\"aliases\":[\"alias\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                + " VALUES (700006, 'ja', 'ALIAS', 'old-key', 1)");
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TagTranslationCatalog.DuplicateExportAliasException collision;
            try {
                TagTranslationCatalog.exportSnapshot(database, state, context.getCacheDir(), () -> false).close();
                fail("Expected a duplicate alias error");
                return;
            } catch (TagTranslationCatalog.DuplicateExportAliasException error) {
                collision = error;
            }
            Intent intent = new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            SettingsActivity activity = (SettingsActivity) instrumentation.startActivitySync(intent);
            try {
                instrumentation.runOnMainSync(() -> {
                    try {
                        Method show = SettingsActivity.class.getDeclaredMethod("showTranslationExportResult",
                            TranslationExportViewModel.Result.class);
                        show.setAccessible(true);
                        show.invoke(activity, new TranslationExportViewModel.Result(collision));
                        Field field = SettingsActivity.class.getDeclaredField("translationExportErrorDialog");
                        field.setAccessible(true);
                        AlertDialog dialog = (AlertDialog) field.get(activity);
                        assertNotNull(dialog);
                        assertTrue(dialog.isShowing());
                        TextView message = dialog.findViewById(android.R.id.message);
                        assertNotNull(message);
                        assertTrue(message.isShown());
                        assertEquals(context.getString(R.string.tag_translation_export_duplicate_alias),
                            message.getText().toString());
                        dialog.dismiss();
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                });
            } finally {
                instrumentation.runOnMainSync(activity::finish);
            }
        } finally {
            context.deleteDatabase("Entries.db");
        }
    }
}
