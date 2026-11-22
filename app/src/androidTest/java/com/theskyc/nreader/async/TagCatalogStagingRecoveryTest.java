package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.ListenableFuture;
import com.theskyc.nreader.TagRecoveryProcess;
import com.theskyc.nreader.async.database.TagCatalog;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kotlin.coroutines.EmptyCoroutineContext;
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
public class TagCatalogStagingRecoveryTest {
    @Test
    public void leavesPartialCatalogStageForExternalProcessStop() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("catalog-stage");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File databaseFile = databaseFile(context, token);
        try (SQLiteDatabase database = openBaseline(databaseFile)) {
            ScrapeTags worker = worker(context, () -> {
                File partial = onlyPartialFile(context);
                assertEquals(candidateBytes().length / 2, partial.length());
                TagRecoveryProcess.awaitExternalStop(token);
            });
            assertEquals(Result.success(), worker.refresh(database));
            fail("The catalog download should have been stopped before completion");
        }
    }

    @Test
    public void removesInterruptedCatalogStageAndInstallsVerifiedSnapshot() throws Exception {
        String token = TagRecoveryProcess.tokenForPhase("verify");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = stagingDirectory(context);
        File partial = onlyPartialFile(context);
        assertEquals(candidateBytes().length / 2, partial.length());

        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile(context, token).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(1, TagCatalog.installedRevision(database));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM TagRules WHERE idTag = 1 AND name = 'before-1' AND type = 1 AND status = 1", null));

            ScrapeTags worker = worker(context, null);
            assertEquals(Result.success(), worker.refresh(database));
            assertEquals(2, TagCatalog.installedRevision(database));
            assertEquals("after-1", DatabaseUtils.stringForQuery(database,
                "SELECT name FROM Tags WHERE idTag = 1", null));
            assertEquals(TagCatalog.MIN_DIRECTORY_ROWS,
                DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM Tags", null));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM TagRules WHERE idTag = 1 AND name = 'before-1' AND type = 1 AND status = 1", null));
            String[] staged = directory.list();
            assertTrue("Staged files should be removed after retry", staged != null && staged.length == 0);
        }
    }

    private static SQLiteDatabase openBaseline(File databaseFile) {
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        database.execSQL("CREATE TABLE Tags (idTag INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL,"
            + " type INTEGER NOT NULL, count INTEGER NOT NULL, provisional INTEGER NOT NULL DEFAULT 0)");
        database.execSQL("CREATE TABLE TagRules (idTag INTEGER PRIMARY KEY CHECK (idTag > 0), name TEXT NOT NULL,"
            + " type INTEGER NOT NULL CHECK (type BETWEEN 1 AND 7), status INTEGER NOT NULL CHECK (status IN (1, 2)))");
        database.execSQL("CREATE TABLE TagCatalogState (source TEXT PRIMARY KEY NOT NULL,"
            + " revision INTEGER NOT NULL CHECK (revision > 0))");
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'before-1', 1, 10)");
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'before-1', 1, 1)");
        database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES ('legacy-upstream', 1)");
        return database;
    }

    private static ScrapeTags worker(Context context, Runnable afterFirstRead) {
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new LegacyWorkerFactory(),
            (app, id, data) -> immediateFuture(),
            (app, id, info) -> immediateFuture());
        byte[] payload = candidateBytes();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.endsWith("tagsVersion")) return response(chain, "2".getBytes(StandardCharsets.US_ASCII));
            BufferedSource source = afterFirstRead == null
                ? new Buffer().write(payload) : pausedSource(payload, afterFirstRead);
            ResponseBody body = new ResponseBody() {
                @Override public MediaType contentType() { return null; }
                @Override public long contentLength() { return payload.length; }
                @Override public BufferedSource source() { return source; }
            };
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic response").body(body).build();
        }).build();
        return new ScrapeTags(context, parameters, "", client);
    }

    private static Response response(okhttp3.Interceptor.Chain chain, byte[] bytes) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Synthetic response")
            .body(ResponseBody.create(new String(bytes, StandardCharsets.US_ASCII), null)).build();
    }

    private static BufferedSource pausedSource(byte[] bytes, Runnable afterFirstRead) {
        return Okio.buffer(new Source() {
            private int offset;

            @Override public long read(Buffer sink, long byteCount) {
                int half = bytes.length / 2;
                if (offset >= half) {
                    afterFirstRead.run();
                    throw new AssertionError("Expected an external process stop");
                }
                int count = (int) Math.min(byteCount, half - offset);
                sink.write(bytes, offset, count);
                offset += count;
                return count;
            }

            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() {}
        });
    }

    private static File databaseFile(Context context, String token) {
        return new File(context.getFilesDir(), "catalog-staging-recovery-" + token + ".sqlite");
    }

    private static File stagingDirectory(Context context) {
        return new File(context.getCacheDir(), "tag-catalog");
    }

    private static File onlyPartialFile(Context context) {
        File[] files = stagingDirectory(context).listFiles((parent, name) -> name.startsWith("catalog-") && name.endsWith(".part"));
        assertTrue("Expected one file from an interrupted catalog download", files != null && files.length == 1);
        return files[0];
    }

    private static byte[] candidateBytes() {
        StringBuilder rows = new StringBuilder("[[1,\"after-1\",20,1]");
        for (int index = 2; index <= TagCatalog.MIN_DIRECTORY_ROWS; index++) {
            rows.append(",[").append(index).append(",\"tag-").append(index).append("\",10,")
                .append((index - 1) % 7 + 1).append(']');
        }
        return rows.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ListenableFuture<Void> immediateFuture() {
        return new ListenableFuture<Void>() {
            @Override public void addListener(Runnable listener, Executor executor) { executor.execute(listener); }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public Void get() { return null; }
            @Override public Void get(long timeout, TimeUnit unit) throws TimeoutException { return null; }
        };
    }
}
