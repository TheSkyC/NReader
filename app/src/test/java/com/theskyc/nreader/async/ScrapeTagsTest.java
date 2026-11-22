package com.theskyc.nreader.async;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.work.Data;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.TagCatalog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import kotlin.coroutines.EmptyCoroutineContext;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ScrapeTagsTest {
    private static final String PUBLISHED_URL = "https://catalog.example/manifest.json";
    private static final String STALE_MANIFEST = "{\"schemaVersion\":1,\"source\":\"nreader-catalog\","
        + "\"kind\":\"catalog\",\"revision\":9,\"sizeBytes\":2,\"sha256\":\"" + "a".repeat(64) + "\"}";
    private Context context;
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        context = RuntimeEnvironment.getApplication();
        helper = new DatabaseHelper(context);
        database = helper.getWritableDatabase();
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (10001, 'installed name', 3, 99)");
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (10001, 'saved name', 3, 1)");
    }

    @After public void closeDatabase() {
        helper.close();
    }

    @Test public void staleLegacyRevisionRetriesWithoutSuppressingTheNextCheck() {
        installState("legacy-upstream");
        AtomicInteger remote = new AtomicInteger(9);
        List<String> requests = new ArrayList<>();
        Function<String, String> server = path -> {
            requests.add(path);
            return Integer.toString(remote.get());
        };
        SharedPreferences checks = checks(false);
        checks.edit().putInt("checked_revision", 8).putLong("checked_at", 123L).apply();

        assertEquals(Result.retry(), worker("", 0, server).refresh(database));
        assertEquals(Result.retry(), worker("", 1, server).refresh(database));
        assertEquals(Result.failure(), worker("", 2, server).refresh(database));
        assertEquals(8, checks.getInt("checked_revision", 0));
        assertEquals(123L, checks.getLong("checked_at", 0L));
        assertEquals(10, TagCatalog.installedRevision(database));
        assertPreservedData();

        remote.set(10);
        assertEquals(Result.success(), worker("", 0, server).refresh(database));
        assertEquals(10, checks.getInt("checked_revision", 0));
        assertTrue(checks.getLong("checked_at", 0L) > 123L);
        assertEquals(Result.success(), worker("", 0, server).refresh(database));
        assertEquals(Collections.nCopies(4, "tagsVersion"), requests);
    }

    @Test public void movingLegacyRevisionDiscardsStagingAndLeavesSuccessfulCheckUnchanged() {
        installState("legacy-upstream");
        String snapshot = updatedSnapshot();
        long installedRows = DatabaseUtils.queryNumEntries(database, "Tags");
        AtomicInteger finalRevision = new AtomicInteger(12);
        SharedPreferences checks = checks(false);
        checks.edit().putInt("checked_revision", 10).putLong("checked_at", 123L).apply();
        List<String> requests = new ArrayList<>();
        Function<String, String> server = path -> {
            requests.add(path);
            if ("tags.json".equals(path)) return snapshot;
            return requests.size() == 1 ? "11" : Integer.toString(finalRevision.get());
        };

        assertEquals(Result.retry(), worker("", 0, server).refresh(database));
        assertEquals(List.of("tagsVersion", "tags.json", "tagsVersion"), requests);
        assertEquals(10, checks.getInt("checked_revision", 0));
        assertEquals(123L, checks.getLong("checked_at", 0L));
        assertEquals(10, TagCatalog.installedRevision(database));
        assertPreservedData();
        assertNoStagedCatalog();

        requests.clear();
        finalRevision.set(11);
        assertEquals(Result.success(), worker("", 1, server).refresh(database));
        assertEquals(List.of("tagsVersion", "tags.json", "tagsVersion"), requests);
        assertEquals(11, TagCatalog.installedRevision(database));
        assertEquals(11, checks.getInt("checked_revision", 0));
        assertTrue(checks.getLong("checked_at", 0L) > 123L);
        assertEquals("updated name", DatabaseUtils.stringForQuery(database, "SELECT name FROM Tags WHERE idTag = 10001", null));
        assertEquals(installedRows + TagCatalog.MIN_DIRECTORY_ROWS - 1, DatabaseUtils.queryNumEntries(database, "Tags"));
        assertPreservedRule();
        assertNoStagedCatalog();
    }

    @Test public void createsCatalogStagingDirectoryWhenCacheRootDoesNotExist() {
        installState("legacy-upstream");
        File cacheRoot = new File(context.getCacheDir(), "missing-cache-parent/" + UUID.randomUUID());
        assertTrue(!cacheRoot.exists());
        Context wrapped = new ContextWrapper(context) {
            @Override public File getCacheDir() {
                return cacheRoot;
            }

            @Override public Context getApplicationContext() {
                return this;
            }
        };
        String snapshot = updatedSnapshot();
        ScrapeTags worker = worker(wrapped, "", 0, path -> "tagsVersion".equals(path) ? "11" : snapshot);

        assertEquals(Result.success(), worker.refresh(database));
        File staging = new File(cacheRoot, "tag-catalog");
        assertTrue(staging.isDirectory());
        assertEquals(0, staging.list().length);
        deleteTree(cacheRoot);
    }

    private static String updatedSnapshot() {
        StringBuilder rows = new StringBuilder("[[10001,\"updated name\",101,3]");
        for (int index = 1; index < TagCatalog.MIN_DIRECTORY_ROWS; index++) {
            rows.append(",[").append(1_000_000 + index).append(",\"tag ").append(index)
                .append("\",1,").append(index % 7 + 1).append(']');
        }
        return rows.append(']').toString();
    }

    private void assertNoStagedCatalog() {
        String[] staged = new File(context.getCacheDir(), "tag-catalog").list();
        assertNotNull(staged);
        assertEquals(0, staged.length);
    }

    @Test public void stalePublishedRevisionDoesNotDownloadOrMarkTheCheckSuccessful() {
        installState("nreader-catalog");
        List<String> requests = new ArrayList<>();
        ScrapeTags worker = worker(PUBLISHED_URL, 0, path -> {
            requests.add(path);
            return STALE_MANIFEST;
        });

        assertEquals(Result.retry(), worker.refresh(database));
        assertEquals(List.of("manifest.json"), requests);
        assertEquals(0L, checks(true).getLong("checked_at", 0L));
        assertEquals(10, TagCatalog.installedPublishedRevision(database));
        assertPreservedData();
    }

    private void installState(String source) {
        database.execSQL("INSERT INTO TagCatalogState (source, revision) VALUES (?, 10)", new Object[]{source});
    }

    private SharedPreferences checks(boolean published) {
        return context.getSharedPreferences(published ? "PublishedTagCatalogChecks" : "TagCatalogChecks", Context.MODE_PRIVATE);
    }

    private void assertPreservedData() {
        assertEquals("installed name", DatabaseUtils.stringForQuery(database, "SELECT name FROM Tags WHERE idTag = 10001", null));
        assertPreservedRule();
    }

    private void assertPreservedRule() {
        assertEquals("saved name", DatabaseUtils.stringForQuery(database, "SELECT name FROM TagRules WHERE idTag = 10001", null));
        assertEquals(1L, DatabaseUtils.longForQuery(database, "SELECT status FROM TagRules WHERE idTag = 10001", null));
    }

    private ScrapeTags worker(String manifestUrl, int attempt, Function<String, String> server) {
        return worker(context, manifestUrl, attempt, server);
    }

    private ScrapeTags worker(Context workerContext, String manifestUrl, int attempt,
                              Function<String, String> server) {
        Executor direct = Runnable::run;
        WorkerParameters params = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), attempt, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new LegacyWorkerFactory(),
            (app, id, data) -> Futures.immediateFuture(null), (app, id, info) -> Futures.immediateFuture(null));
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            List<String> segments = chain.request().url().pathSegments();
            String name = segments.get(segments.size() - 1);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Fixture").body(ResponseBody.create(server.apply(name), null)).build();
        }).build();
        return new ScrapeTags(workerContext, params, manifestUrl, client);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        assertTrue(file.delete());
    }
}
