package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.theskyc.nreader.TagRecoveryProcess;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.async.database.TranslationRecoveryFixture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

@RunWith(AndroidJUnit4.class)
public class TagTranslationStagingRecoveryTest {
    private static final String MANIFEST_URL = "https://feed.example/tags/manifest.json";

    @Test
    public void leavesPartialTranslationStageForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("stage");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "tag-translation-feed");
        File databaseFile = TranslationRecoveryFixture.databaseFile(context, token);
        try (SQLiteDatabase database = TranslationRecoveryFixture.createBaseline(databaseFile)) {
            TagTranslationFeed feed = feed(context, () -> {
                File partial = onlyPartialFile(directory);
                assertEquals(halfPayloadSize(), partial.length());
                TagRecoveryProcess.awaitExternalStop(token);
            });

            feed.install(database, candidate(feed));
            fail("The download should have been stopped before receiving the full payload");
        }
    }

    @Test
    public void removesInterruptedTranslationStageAndInstallsVerifiedPack() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "tag-translation-feed");
        File partial = onlyPartialFile(directory);
        assertEquals(halfPayloadSize(), partial.length());

        try (SQLiteDatabase database = TranslationRecoveryFixture.reopen(
                TranslationRecoveryFixture.databaseFile(context, token))) {
            TranslationRecoveryFixture.assertInstalled(database, 1, "before");
            TagTranslationFeed feed = feed(context, null);
            assertTrue(feed.install(database, candidate(feed)));
            TranslationRecoveryFixture.assertInstalled(database, 2, "after");
            String[] stagedFiles = directory.list();
            assertTrue("Staged files should be removed after retry", stagedFiles != null && stagedFiles.length == 0);
        }
    }

    private static TagTranslationManifest.Pack candidate(TagTranslationFeed feed) throws IOException {
        return feed.readManifest().getPacks().get("ja");
    }

    private static File onlyPartialFile(File directory) {
        File[] files = directory.listFiles((parent, name) -> name.startsWith("translation-") && name.endsWith(".part"));
        assertTrue("Expected one file from an interrupted feed download", files != null && files.length == 1);
        return files[0];
    }

    private static int halfPayloadSize() {
        return TranslationRecoveryFixture.payload(2, "after").length / 2;
    }

    private static TagTranslationFeed feed(Context context, Runnable afterFirstRead) throws Exception {
        byte[] payload = TranslationRecoveryFixture.payload(2, "after");
        StringBuilder digest = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(payload)) {
            digest.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        String manifest = "{\"schemaVersion\":1,\"source\":\"nreader-translations\","
            + "\"kind\":\"translations\",\"packs\":[{\"locale\":\"ja\",\"revision\":2,"
            + "\"sizeBytes\":" + payload.length + ",\"sha256\":\"" + digest + "\"}]}";
        return new TagTranslationFeed(context.getCacheDir(), MANIFEST_URL,
            new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean isManifest = chain.request().url().encodedPath().endsWith("manifest.json");
                byte[] bytes = isManifest ? manifest.getBytes(StandardCharsets.UTF_8) : payload;
                BufferedSource source = isManifest || afterFirstRead == null
                    ? new Buffer().write(bytes) : pausedSource(bytes, afterFirstRead);
                ResponseBody responseBody = new ResponseBody() {
                    @Override public MediaType contentType() { return null; }
                    @Override public long contentLength() { return bytes.length; }
                    @Override public BufferedSource source() { return source; }
                };
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic response").body(responseBody).build();
            }).build());
    }

    private static BufferedSource pausedSource(byte[] bytes, Runnable afterFirstRead) {
        return Okio.buffer(new Source() {
            private boolean sentFirstChunk;

            @Override
            public long read(Buffer sink, long byteCount) {
                if (sentFirstChunk) {
                    afterFirstRead.run();
                    throw new AssertionError("Expected an external process stop");
                }
                int count = bytes.length / 2;
                assertTrue(byteCount >= count);
                sink.write(bytes, 0, count);
                sentFirstChunk = true;
                return count;
            }

            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() {}
        });
    }
}
