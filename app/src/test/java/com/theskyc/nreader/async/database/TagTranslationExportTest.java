package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationExportTest {
    @Rule public TemporaryFolder scratch = new TemporaryFolder();
    private SQLiteDatabase database;

    @Before public void setUp() {
        database = SQLiteDatabase.create(null);
        Queries.TagTable.createTables(database);
        TagTranslationCatalog.createTables(database);
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (1, 'canonical', 3, 1),"
            + " (2, 'other', 3, 1)");
    }

    @After public void tearDown() {
        database.close();
        assertEquals(0, scratch.getRoot().list().length);
    }

    private boolean install(String json) throws IOException {
        return TagTranslationCatalog.apply(database, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
            scratch.getRoot(), () -> false);
    }

    private TagTranslationCatalog.PackState state(String locale) {
        return TagTranslationCatalog.installedPackStates(database).get(locale);
    }

    private byte[] export(TagTranslationCatalog.PackState state, int limit) throws IOException {
        try (SeekableByteChannel file = TagTranslationCatalog.exportSnapshot(database, state, scratch.getRoot(),
            () -> false, limit)) {
            return Channels.newInputStream(file).readAllBytes();
        }
    }

    @Test public void exportRoundTripsUnicodeAndAliasOrderForOnlyTheSelectedLocale() throws Exception {
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":4,\"entries\":["
            + "{\"id\":2,\"name\":\"two\"},{\"id\":1,\"name\":\"\u753b\ud83d\ude00\","
            + "\"aliases\":[\"zebra\",\"alpha\"]}]}"));
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"zh-Hans\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"other language\"}]}"));
        database.execSQL("INSERT INTO TagRules (idTag, name, type, status) VALUES (1, 'private-rule-marker', 3, 1)");
        byte[] snapshot = export(state("ja"), TagTranslationCatalog.MAX_PACK_BYTES);
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertFalse(json.contains("private-rule-marker"));
        assertFalse(json.contains("other language"));
        JSONObject pack = new JSONObject(json);
        assertEquals("ja", pack.getString("locale"));
        assertEquals(4, pack.getInt("revision"));
        JSONArray entries = pack.getJSONArray("entries");
        assertEquals(2, entries.length());
        assertEquals(1, entries.getJSONObject(0).getInt("id"));
        assertEquals("\u753b\ud83d\ude00", entries.getJSONObject(0).getString("name"));
        assertEquals("zebra", entries.getJSONObject(0).getJSONArray("aliases").getString(0));
        assertEquals("alpha", entries.getJSONObject(0).getJSONArray("aliases").getString(1));
        TagTranslationCatalog.remove(database, "ja");
        assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(snapshot), scratch.getRoot(), () -> false));
        assertArrayEquals(snapshot, export(state("ja"), TagTranslationCatalog.MAX_PACK_BYTES));
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "zh-Hans"));
    }

    @Test public void rejectsStaleSelectionAndUnknownOrInvalidOrderWithoutChangingData() throws Exception {
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"one\",\"aliases\":[\"z\",\"a\"]}]}"));
        TagTranslationCatalog.PackState stale = state("ja");
        TagTranslationCatalog.remove(database, "ja");
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"one\",\"aliases\":[\"z\",\"a\"]}]}"));
        assertThrows(TagTranslationCatalog.ChangedDuringExportException.class,
            () -> export(stale, TagTranslationCatalog.MAX_PACK_BYTES));
        database.execSQL("UPDATE TagTranslationAliases SET aliasOrder = -1 WHERE alias = 'z'");
        assertThrows(TagTranslationCatalog.UnknownAliasOrderException.class,
            () -> export(state("ja"), TagTranslationCatalog.MAX_PACK_BYTES));
        database.execSQL("UPDATE TagTranslationAliases SET aliasOrder = 3 WHERE alias = 'z'");
        assertThrows(TagTranslationCatalog.UnknownAliasOrderException.class,
            () -> export(state("ja"), TagTranslationCatalog.MAX_PACK_BYTES));
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
    }

    @Test public void enforcesByteLimitAndCleansCancelledSnapshots() throws Exception {
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"\u753b\",\"aliases\":[\"a\"]}]}"));
        TagTranslationCatalog.PackState state = state("ja");
        byte[] snapshot = export(state, TagTranslationCatalog.MAX_PACK_BYTES);
        assertArrayEquals(snapshot, export(state, snapshot.length));
        assertThrows(IOException.class, () -> export(state, snapshot.length - 1));
        assertThrows(IOException.class, () -> TagTranslationCatalog.exportSnapshot(database, state,
            scratch.getRoot(), () -> database.inTransaction()));
        assertFalse(database.inTransaction());
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
    }

    @Test public void snapshotRemainsConsistentWhenRemovalStartsDuringExport() throws Exception {
        assertTrue(install("{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
            + "\"entries\":[{\"id\":1,\"name\":\"one\",\"aliases\":[\"z\",\"a\"]}]}"));
        TagTranslationCatalog.PackState state = state("ja");
        CountDownLatch insideTransaction = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch removalStarted = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> snapshot = executor.submit(() -> {
                try (SeekableByteChannel file = TagTranslationCatalog.exportSnapshot(database, state, scratch.getRoot(), () -> {
                    if (database.inTransaction() && paused.compareAndSet(false, true)) {
                        insideTransaction.countDown();
                        try {
                            return !releaseSnapshot.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return true;
                        }
                    }
                    return false;
                })) {
                    return Channels.newInputStream(file).readAllBytes();
                }
            });
            assertTrue(insideTransaction.await(10, TimeUnit.SECONDS));
            Future<?> removal = executor.submit(() -> {
                removalStarted.countDown();
                TagTranslationCatalog.remove(database, "ja");
            });
            assertTrue(removalStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> removal.get(200, TimeUnit.MILLISECONDS));
            releaseSnapshot.countDown();
            JSONObject pack = new JSONObject(new String(snapshot.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            removal.get(10, TimeUnit.SECONDS);
            assertEquals(1, pack.getInt("revision"));
            JSONArray aliases = pack.getJSONArray("entries").getJSONObject(0).getJSONArray("aliases");
            assertEquals("z", aliases.getString(0));
            assertEquals("a", aliases.getString(1));
            assertEquals(0, TagTranslationCatalog.installedRevision(database, "ja"));
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
