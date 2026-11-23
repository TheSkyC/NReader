package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.work.Configuration;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Global;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TranslationFailureDialogTest {
    @Rule public final WorkManagerTestScope workManagerScope = new WorkManagerTestScope();
    private TagTranslationCatalog.UnknownTagException unknownTag() throws Exception {
        Constructor<TagTranslationCatalog.UnknownTagException> constructor =
            TagTranslationCatalog.UnknownTagException.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test public void missingTagsWithoutCatalogSourceOffersOfflineRecovery() throws Exception {
        assumeTrue(BuildConfig.TAG_CATALOG_MANIFEST_URL.isEmpty());
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        try (DatabaseHelper database = new DatabaseHelper(context)) {
            database.getWritableDatabase();
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity activity = controller.get();
                ReflectionHelpers.callInstanceMethod(activity, "showTranslationFailure",
                    ReflectionHelpers.ClassParameter.from(Throwable.class, unknownTag()),
                    ReflectionHelpers.ClassParameter.from(int.class, R.string.tag_translation_install_failed));
                Dialog latest = ShadowDialog.getLatestDialog();
                assertTrue(latest instanceof AlertDialog);
                AlertDialog dialog = (AlertDialog) latest;
                TextView message = dialog.findViewById(android.R.id.message);
                assertEquals(activity.getString(R.string.tag_translation_unknown_tags_no_source),
                    message.getText().toString());
                assertEquals(activity.getString(android.R.string.ok),
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
                CatalogRefreshViewModel model = ReflectionHelpers.getField(activity, "catalogRefresh");
                assertNull(model.requestId());
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNull(model.requestId());
            }
        }
    }

    @Test public void missingTagsWithCatalogSourceOffersAnUpdateCheck() throws Exception {
        assumeTrue(!BuildConfig.TAG_CATALOG_MANIFEST_URL.isEmpty());
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            new Configuration.Builder().setExecutor(executor).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        try (DatabaseHelper database = new DatabaseHelper(context)) {
            database.getWritableDatabase();
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity activity = controller.get();
                ReflectionHelpers.callInstanceMethod(activity, "showTranslationFailure",
                    ReflectionHelpers.ClassParameter.from(Throwable.class, unknownTag()),
                    ReflectionHelpers.ClassParameter.from(int.class, R.string.tag_translation_install_failed));
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertEquals(activity.getString(R.string.tag_catalog_check_updates),
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                CatalogRefreshViewModel model = ReflectionHelpers.getField(activity, "catalogRefresh");
                assertTrue(model.requestId() != null);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
