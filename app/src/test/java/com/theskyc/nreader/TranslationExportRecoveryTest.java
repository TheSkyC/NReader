package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;
import com.theskyc.nreader.settings.Global;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TranslationExportRecoveryTest {
    @Test public void selectionAndRunningExportSurviveActivityRecreation() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        SQLiteDatabase previous = Database.getDatabase();
        File file = File.createTempFile("translation-export", ".json", context.getCacheDir());
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700005, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700005,\"name\":\"translated\",\"aliases\":[\"z\",\"a\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TestProvider provider = new TestProvider(file, database, opening, resume, false);
            ProviderInfo info = new ProviderInfo();
            info.authority = "translation-export-test";
            provider.attachInfo(context, info);
            ShadowContentResolver.registerProviderInternal(info.authority, provider);
            Uri destination = Uri.parse("content://translation-export-test/pack");
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity original = controller.get();
                Map<String, TagTranslationCatalog.PackState> packs = Collections.singletonMap("ja",
                    TagTranslationCatalog.installedPackStates(database).get("ja"));
                ReflectionHelpers.callInstanceMethod(original, "showTranslationPacks",
                    ReflectionHelpers.ClassParameter.from(Map.class, packs));
                AlertDialog list = (AlertDialog) ShadowDialog.getLatestDialog();
                list.getListView().performItemClick(list.getListView().getChildAt(0), 0, 0);
                AlertDialog actions = (AlertDialog) ShadowDialog.getLatestDialog();
                actions.getListView().performItemClick(actions.getListView().getChildAt(0), 0, 0);
                controller.recreate();
                SettingsActivity recreated = controller.get();
                TranslationExportViewModel.Selection selection = ReflectionHelpers.getField(recreated, "pendingTranslationExport");
                assertNotNull(selection);
                assertEquals("ja", selection.locale);
                assertEquals(1, selection.revision);
                ReflectionHelpers.callInstanceMethod(recreated, "handleTranslationExportResult",
                    ReflectionHelpers.ClassParameter.from(Uri.class, destination));
                TranslationExportViewModel model = ReflectionHelpers.getField(recreated, "translationExport");
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                assertFalse(provider.openedInsideTransaction);
                controller.recreate();
                assertSame(model, ReflectionHelpers.getField(controller.get(), "translationExport"));
                resume.countDown();
                awaitResult(model);
                assertNull(model.result().getValue().error);
                JSONObject output = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                assertEquals("ja", output.getString("locale"));
                assertEquals("z", output.getJSONArray("entries").getJSONObject(0).getJSONArray("aliases").getString(0));
                assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            }
        } finally {
            resume.countDown();
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            assertTrue(file.delete());
        }
    }

    @Test public void pickerResultIsDeliveredAfterFreshActivityRestoresSelection() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        SQLiteDatabase previous = Database.getDatabase();
        File file = File.createTempFile("translation-export-restored", ".json", context.getCacheDir());
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700009, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700009,\"name\":\"translated\"}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TestProvider provider = new TestProvider(file, database, new CountDownLatch(0), new CountDownLatch(0), false);
            ProviderInfo info = new ProviderInfo();
            info.authority = "translation-export-restored";
            provider.attachInfo(context, info);
            ShadowContentResolver.registerProviderInternal(info.authority, provider);
            Bundle state = new Bundle();
            int requestCode;
            try (ActivityController<SettingsActivity> original = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                TagTranslationCatalog.PackState packState = TagTranslationCatalog.installedPackStates(database).get("ja");
                ReflectionHelpers.callInstanceMethod(original.get(), "beginTranslationExport",
                    ReflectionHelpers.ClassParameter.from(String.class, "ja"),
                    ReflectionHelpers.ClassParameter.from(TagTranslationCatalog.PackState.class, packState));
                requestCode = Shadows.shadowOf(original.get()).getNextStartedActivityForResult().requestCode;
                original.pause().saveInstanceState(state).stop();
            }
            Parcel parcel = Parcel.obtain();
            Bundle restoredState;
            try {
                state.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                restoredState = Bundle.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
            try (ActivityController<SettingsActivity> restored = Robolectric.buildActivity(SettingsActivity.class).create(restoredState)) {
                Intent result = new Intent().setData(Uri.parse("content://translation-export-restored/pack"));
                assertTrue(restored.get().getActivityResultRegistry().dispatchResult(requestCode, Activity.RESULT_OK, result));
                restored.start().resume().visible();
                TranslationExportViewModel model = ReflectionHelpers.getField(restored.get(), "translationExport");
                awaitResult(model);
                assertNull(model.result().getValue().error);
                assertEquals("ja", new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                    .getString("locale"));
                assertEquals(1, provider.opens);
            }
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            assertTrue(file.delete());
        }
    }

    @Test public void staleSelectionAndProviderFailureReportFailureWithoutChangingThePack() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        File file = File.createTempFile("translation-export-error", ".json", context.getCacheDir());
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700006, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700006,\"name\":\"translated\",\"aliases\":[\"alias\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TranslationExportViewModel.Selection selection = new TranslationExportViewModel.Selection("ja", state.revision, state.generation);
            TestProvider provider = new TestProvider(file, database, new CountDownLatch(0), new CountDownLatch(0), true);
            ProviderInfo info = new ProviderInfo();
            info.authority = "translation-export-error";
            provider.attachInfo(context, info);
            ShadowContentResolver.registerProviderInternal(info.authority, provider);
            Uri destination = Uri.parse("content://translation-export-error/pack");
            TranslationExportViewModel model = new TranslationExportViewModel();
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertNotNull(model.result().getValue().error);
            assertEquals(1, provider.opens);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            database.execSQL("UPDATE TagTranslationAliases SET aliasOrder = -1 WHERE locale = 'ja'");
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertTrue(model.result().getValue().error instanceof TagTranslationCatalog.UnknownAliasOrderException);
            assertEquals(1, provider.opens);
            database.execSQL("UPDATE TagTranslationAliases SET aliasOrder = 0 WHERE locale = 'ja'");
            database.execSQL("INSERT INTO TagTranslationAliases (idTag, locale, alias, aliasKey, aliasOrder)"
                + " VALUES (700006, 'ja', 'ALIAS', 'old-key', 1)");
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertTrue(model.result().getValue().error instanceof TagTranslationCatalog.DuplicateExportAliasException);
            assertEquals(1, provider.opens);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            Global.initFromShared(context);
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
                ReflectionHelpers.callInstanceMethod(controller.get(), "showTranslationExportResult",
                    ReflectionHelpers.ClassParameter.from(TranslationExportViewModel.Result.class, model.result().getValue()));
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                TextView message = dialog.findViewById(android.R.id.message);
                assertNotNull(message);
                assertEquals(context.getString(R.string.tag_translation_export_duplicate_alias), message.getText().toString());
                dialog.dismiss();
            }
            database.execSQL("DELETE FROM TagTranslationAliases WHERE idTag = 700006 AND aliasOrder = 1");
            AtomicInteger written = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            TranslationExportViewModel failingWrite = new TranslationExportViewModel((app, uri) -> new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (written.get() == 8) throw new IOException("Synthetic write failure");
                    written.incrementAndGet();
                }
                @Override public void close() { closes.incrementAndGet(); }
            });
            assertTrue(failingWrite.start(context, destination, selection));
            awaitResult(failingWrite);
            assertNotNull(failingWrite.result().getValue().error);
            assertEquals(8, written.get());
            assertEquals(1, closes.get());
            TranslationExportViewModel failingClose = new TranslationExportViewModel((app, uri) -> new ByteArrayOutputStream() {
                @Override public void close() throws IOException { throw new IOException("Synthetic close failure"); }
            });
            assertTrue(failingClose.start(context, destination, selection));
            awaitResult(failingClose);
            assertNotNull(failingClose.result().getValue().error);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            TagTranslationCatalog.remove(database, "ja");
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertTrue(model.result().getValue().error instanceof TranslationExportViewModel.ChangedSelectionException);
            assertEquals(1, provider.opens);
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            assertTrue(file.delete());
        }
    }

    @Test public void partialWriteFailureCanBeRetriedWithoutChangingInstalledPack() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        AtomicInteger attempts = new AtomicInteger();
        ByteArrayOutputStream retryOutput = new ByteArrayOutputStream();
        TranslationExportViewModel model = new TranslationExportViewModel((app, uri) -> {
            if (attempts.incrementAndGet() > 1) return retryOutput;
            return new OutputStream() {
                private int written;
                @Override public void write(int value) throws IOException {
                    if (written++ == 8) throw new IOException("Synthetic partial write failure");
                }
            };
        });
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700010, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700010,\"name\":\"translated\",\"aliases\":[\"z\",\"a\"]}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TranslationExportViewModel.Selection selection = new TranslationExportViewModel.Selection("ja", state.revision, state.generation);
            Uri destination = Uri.parse("content://translation-export-test/pack");
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertNotNull(model.result().getValue().error);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            assertTrue(model.start(context, destination, selection));
            awaitResult(model);
            assertNull(model.result().getValue().error);
            JSONObject exported = new JSONObject(retryOutput.toString("UTF-8"));
            assertEquals("ja", exported.getString("locale"));
            assertEquals("z", exported.getJSONArray("entries").getJSONObject(0).getJSONArray("aliases").getString(0));
            assertEquals(2, attempts.get());
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        } finally {
            if (model.isRunning()) awaitResult(model);
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
        }
    }

    @Test public void cancellationWhileProviderOpensDoesNotReportSuccess() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        File file = File.createTempFile("translation-export-cancel", ".json", context.getCacheDir());
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700007, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700007,\"name\":\"translated\"}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TestProvider provider = new TestProvider(file, database, opening, resume, false);
            ProviderInfo info = new ProviderInfo();
            info.authority = "translation-export-cancel";
            provider.attachInfo(context, info);
            ShadowContentResolver.registerProviderInternal(info.authority, provider);
            TranslationExportViewModel model = new TranslationExportViewModel();
            try {
                assertTrue(model.start(context, Uri.parse("content://translation-export-cancel/pack"),
                    new TranslationExportViewModel.Selection("ja", state.revision, state.generation)));
                assertTrue(opening.await(10, TimeUnit.SECONDS));
                model.cancel();
                resume.countDown();
                awaitResult(model);
                assertTrue(model.result().getValue().error instanceof InterruptedIOException);
                assertEquals(0, file.length());
                assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            } finally {
                resume.countDown();
                if (model.isRunning()) awaitResult(model);
            }
        } finally {
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
            assertTrue(file.delete());
        }
    }

    @Test public void cancellationWhileProviderClosesDoesNotReportSuccess() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        SQLiteDatabase previous = Database.getDatabase();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        TranslationExportViewModel model = new TranslationExportViewModel((app, uri) -> new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                closing.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("Close timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Close interrupted");
                }
                super.close();
            }
        });
        try (DatabaseHelper helper = new DatabaseHelper(context)) {
            SQLiteDatabase database = helper.getWritableDatabase();
            Database.setDatabase(database);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700008, 'fixture', 3, 1)");
            String pack = "{\"schemaVersion\":2,\"locale\":\"ja\",\"revision\":1,"
                + "\"entries\":[{\"id\":700008,\"name\":\"translated\"}]}";
            assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                context.getCacheDir(), () -> false));
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            assertTrue(model.start(context, Uri.parse("content://translation-export-test/pack"),
                new TranslationExportViewModel.Selection("ja", state.revision, state.generation)));
            assertTrue(closing.await(10, TimeUnit.SECONDS));
            model.cancel();
            resume.countDown();
            awaitResult(model);
            assertTrue(model.result().getValue().error instanceof InterruptedIOException);
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        } finally {
            resume.countDown();
            if (model.isRunning()) awaitResult(model);
            ReflectionHelpers.setStaticField(Database.class, "database", previous);
            Queries.setDb(previous);
        }
    }

    private static void awaitResult(TranslationExportViewModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (model.result().getValue() == null && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertNotNull(model.result().getValue());
    }

    private static final class TestProvider extends ContentProvider {
        private final File file;
        private final SQLiteDatabase database;
        private final CountDownLatch opening;
        private final CountDownLatch resume;
        private final boolean fail;
        volatile boolean openedInsideTransaction;
        volatile int opens;

        TestProvider(File file, SQLiteDatabase database, CountDownLatch opening, CountDownLatch resume, boolean fail) {
            this.file = file;
            this.database = database;
            this.opening = opening;
            this.resume = resume;
            this.fail = fail;
        }

        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            opens++;
            openedInsideTransaction = database.inTransaction();
            opening.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) throw new FileNotFoundException("Provider timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("Provider interrupted");
            }
            if (fail) throw new FileNotFoundException("Synthetic provider failure");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
