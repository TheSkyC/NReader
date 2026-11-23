package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Looper;
import android.widget.TextView;

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

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TranslationImportRecoveryTest {
    @Test public void localDocumentUsesTheRealImporter() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        File pack = File.createTempFile("tag-translation-test", ".json", context.getCacheDir());
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700001, 'fixture', 3, 1)");
            String json = "{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700001,\"name\":\"fixture translation\"}]}";
            Files.write(pack.toPath(), json.getBytes(StandardCharsets.UTF_8));
            TranslationImportViewModel model = new TranslationImportViewModel();
            assertTrue(model.start(context, Uri.fromFile(pack)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (model.result().getValue() == null && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            TranslationImportViewModel.Result result = model.result().getValue();
            assertNotNull(result);
            assertTrue(result.changed);
            assertNull(result.error);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            assertTrue(pack.delete());
        }
    }

    @Test public void successfulImportReportsOnceAfterRecreation() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DatabaseHelper database = new DatabaseHelper(context)) {
            database.getWritableDatabase();
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity original = controller.get();
                TranslationImportViewModel model = ReflectionHelpers.getField(original, "translationImport");
                TranslationImportViewModel.ImportOperation operation = (app, uri) -> {
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Import timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return true;
                };
                ReflectionHelpers.setField(model, "operation", operation);
                assertTrue(model.start(original, Uri.parse("content://test.invalid/pack")));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                controller.recreate();
                SettingsActivity recreated = controller.get();
                assertSame(model, ReflectionHelpers.getField(recreated, "translationImport"));
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (model.result().getValue() == null && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertNotNull(model.result().getValue());
                assertEquals(recreated.getString(R.string.tag_translation_installed),
                    ShadowToast.getTextOfLatestToast());
                assertFalse(model.result().getValue().consume());
            }
        } finally {
            release.countDown();
        }
    }

    @Test public void failedImportSurvivesRecreationAndRejectsAnOverlappingImport() throws Exception {
        assumeTrue(BuildConfig.TAG_CATALOG_MANIFEST_URL.isEmpty());
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        Constructor<TagTranslationCatalog.UnknownTagException> constructor =
            TagTranslationCatalog.UnknownTagException.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TagTranslationCatalog.UnknownTagException failure = constructor.newInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DatabaseHelper database = new DatabaseHelper(context)) {
            database.getWritableDatabase();
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity original = controller.get();
                TranslationImportViewModel model = ReflectionHelpers.getField(original, "translationImport");
                TranslationImportViewModel.ImportOperation operation = (app, uri) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Import timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    throw failure;
                };
                ReflectionHelpers.setField(model, "operation", operation);
                Uri uri = Uri.parse("content://test.invalid/pack");
                ReflectionHelpers.callInstanceMethod(original, "importTranslationPack",
                    ReflectionHelpers.ClassParameter.from(Uri.class, uri));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                controller.recreate();
                SettingsActivity recreated = controller.get();
                assertSame(model, ReflectionHelpers.getField(recreated, "translationImport"));
                ReflectionHelpers.callInstanceMethod(recreated, "importTranslationPack",
                    ReflectionHelpers.ClassParameter.from(Uri.class, uri));
                assertEquals(recreated.getString(R.string.tag_translation_import_in_progress),
                    ShadowToast.getTextOfLatestToast());
                assertEquals(1, calls.get());
                release.countDown();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (model.result().getValue() == null && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertNotNull(model.result().getValue());
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(dialog.isShowing());
                assertEquals(recreated.getString(R.string.tag_translation_unknown_tags_no_source),
                    ((TextView) dialog.findViewById(android.R.id.message)).getText().toString());
                assertFalse(model.result().getValue().consume());
            }
        } finally {
            release.countDown();
        }
    }
}
