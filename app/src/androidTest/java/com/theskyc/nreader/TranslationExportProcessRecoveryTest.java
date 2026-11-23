package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TranslationRecoveryFixture;
import com.theskyc.nreader.settings.Database;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class TranslationExportProcessRecoveryTest {
    @Test public void holdsPartialExportForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("translation-export");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SQLiteDatabase database = TranslationRecoveryFixture.createBaseline(
            TranslationRecoveryFixture.databaseFile(context, token), context.getCacheDir());
        database.execSQL("CREATE TABLE Status (name TEXT PRIMARY KEY, color INT NOT NULL)");
        Database.setDatabase(database);
        TranslationExportViewModel model = new TranslationExportViewModel((app, uri) -> {
            FileOutputStream file = new FileOutputStream(outputFile(context, token));
            return new OutputStream() {
                @Override public void write(int value) throws IOException { file.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    file.write(bytes, offset, Math.min(length, 8));
                    file.getFD().sync();
                    TagRecoveryProcess.awaitExternalStop(token);
                }
                @Override public void close() throws IOException { file.close(); }
            };
        });
        TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue(model.start(context,
            Uri.parse("content://export-recovery/pack"),
            new TranslationExportViewModel.Selection("ja", state.revision, state.generation))));
        Thread.sleep(TimeUnit.MINUTES.toMillis(3));
        throw new AssertionError("Export did not reach the external stop marker");
    }

    @Test public void interruptedExportLeavesPackIntactAndCanBeRetried() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = outputFile(context, token);
        assertEquals(8, output.length());
        assertEquals(0, context.getCacheDir().list((dir, name) ->
            name.startsWith("tag-translation-") && name.endsWith(".tmp")).length);
        try (SQLiteDatabase database = TranslationRecoveryFixture.reopen(
                TranslationRecoveryFixture.databaseFile(context, token))) {
            Database.setDatabase(database);
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
            TagTranslationCatalog.PackState state = TagTranslationCatalog.installedPackStates(database).get("ja");
            TranslationExportViewModel model = new TranslationExportViewModel((app, uri) -> new FileOutputStream(output));
            CountDownLatch completed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.result().observeForever(result -> { if (result != null) completed.countDown(); });
                assertTrue(model.start(context, Uri.parse("content://export-recovery/pack"),
                    new TranslationExportViewModel.Selection("ja", state.revision, state.generation)));
            });
            assertTrue("Retry timed out", completed.await(30, TimeUnit.SECONDS));
            assertNull(model.result().getValue().error);
            JSONObject pack = new JSONObject(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
            assertEquals("ja", pack.getString("locale"));
            assertEquals(1, pack.getInt("revision"));
            assertEquals(1, pack.getJSONArray("entries").getJSONObject(0).getInt("id"));
            assertEquals("before", pack.getJSONArray("entries").getJSONObject(0).getString("name"));
            assertEquals("before-alias", pack.getJSONArray("entries").getJSONObject(0)
                .getJSONArray("aliases").getString(0));
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
        }
    }

    private static File outputFile(Context context, String token) {
        return new File(context.getFilesDir(), "translation-export-" + token + ".json");
    }
}
