package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TranslationRecoveryFixture;
import com.theskyc.nreader.settings.Database;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class TranslationExportMediaStoreRecoveryTest {
    private static final String PREFERENCES = "tag-export-recovery";

    @Test public void holdsMediaStoreExportForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("translation-export-media");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SQLiteDatabase database = TranslationRecoveryFixture.createBaseline(
            TranslationRecoveryFixture.databaseFile(context, token), context.getCacheDir());
        database.execSQL("CREATE TABLE Status (name TEXT PRIMARY KEY, color INT NOT NULL)");
        Database.setDatabase(database);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "tag-export-" + token + ".json");
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(destination);
        assertTrue(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(token, destination.toString()).commit());
        TranslationExportViewModel model = new TranslationExportViewModel((app, uri) -> {
            OutputStream provider = app.getContentResolver().openOutputStream(uri, "wt");
            if (provider == null) throw new IOException("MediaStore did not open the destination");
            return new OutputStream() {
                @Override public void write(int value) throws IOException { provider.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    provider.write(bytes, offset, Math.min(length, 8));
                    provider.flush();
                    TagRecoveryProcess.awaitExternalStop(token);
                }
                @Override public void close() throws IOException { provider.close(); }
            };
        });
        TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue(model.start(context, destination,
            new TranslationExportViewModel.Selection("ja", state.revision, state.generation))));
        Thread.sleep(TimeUnit.MINUTES.toMillis(3));
        throw new AssertionError("Export did not reach the external stop marker");
    }

    @Test public void interruptedMediaStoreExportCanBeRetried() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(token, null);
        assertNotNull("Interrupted phase did not save the destination", saved);
        Uri destination = Uri.parse(saved);
        try (SQLiteDatabase database = TranslationRecoveryFixture.reopen(
                TranslationRecoveryFixture.databaseFile(context, token))) {
            Database.setDatabase(database);
            assertEquals(8, read(context, destination).length);
            assertEquals(0, context.getCacheDir().list((dir, name) ->
                name.startsWith("tag-translation-") && name.endsWith(".tmp")).length);
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TranslationExportViewModel model = new TranslationExportViewModel();
            CountDownLatch completed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.result().observeForever(result -> { if (result != null) completed.countDown(); });
                assertTrue(model.start(context, destination,
                    new TranslationExportViewModel.Selection("ja", state.revision, state.generation)));
            });
            assertTrue("Retry timed out", completed.await(30, TimeUnit.SECONDS));
            assertNull(model.result().getValue().error);
            JSONObject pack = new JSONObject(new String(read(context, destination), StandardCharsets.UTF_8));
            assertEquals("ja", pack.getString("locale"));
            assertEquals(1, pack.getInt("revision"));
            assertEquals(1, pack.getJSONArray("entries").getJSONObject(0).getInt("id"));
            assertEquals("before", pack.getJSONArray("entries").getJSONObject(0).getString("name"));
            assertEquals("before-alias", pack.getJSONArray("entries").getJSONObject(0)
                .getJSONArray("aliases").getString(0));
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
        } finally {
            context.getContentResolver().delete(destination, null, null);
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(token).commit();
        }
    }

    private static byte[] read(Context context, Uri destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(destination);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("MediaStore did not open the document");
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
