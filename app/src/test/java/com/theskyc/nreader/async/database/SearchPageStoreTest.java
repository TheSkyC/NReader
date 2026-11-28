package com.theskyc.nreader.async.database;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchPageStoreTest {
    private SQLiteDatabase database;
    private SearchPageStore store;

    @Before public void setUp() {
        database = SQLiteDatabase.create(null);
        SearchPageStore.createTable(database);
        store = new SearchPageStore(database);
    }

    @After public void tearDown() {
        database.close();
    }

    @Test public void cacheIsScopedToIdentityAndCompleteRequestUrlWithoutPersistingEither() throws Exception {
        String identity = "account-fingerprint";
        String url = "https://nhentai.net/api/v2/search?query=private+query&page=2";
        SearchPageStore.Request write = store.beginRequest(identity, url);
        assertTrue(store.save(write, response(101, "cached"), 5, 100));

        try (Cursor cursor = database.query(SearchPageStore.TABLE_NAME,
            new String[]{"identity_hash", "request_hash", "payload"}, null, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(64, cursor.getString(0).length());
            assertEquals(64, cursor.getString(1).length());
            assertFalse(cursor.getString(0).contains(identity));
            assertFalse(cursor.getString(1).contains(url));
            assertFalse(cursor.getString(2).contains("private query"));
        }

        SearchPageStore.Request same = store.beginRequest(identity, url);
        assertNotNull(store.load(same, 200));
        store.finishRequest(same);

        SearchPageStore.Request otherIdentity = store.beginRequest("other-account", url);
        assertNull(store.load(otherIdentity, 300));
        store.finishRequest(otherIdentity);

        SearchPageStore.Request otherSearch = store.beginRequest(identity,
            "https://nhentai.net/api/v2/search?query=other&page=2");
        assertNull(store.load(otherSearch, 400));
        store.finishRequest(otherSearch);
        store.finishRequest(write);
    }

    @Test public void lateOlderResponseCannotReplaceNewerPage() throws Exception {
        String url = "https://example.test/api/v2/search?query=term&page=1";
        SearchPageStore.Request older = store.beginRequest("public", url);
        SearchPageStore.Request newer = store.beginRequest("public", url);

        assertFalse(store.save(older, response(201, "old"), 3, 100));
        assertTrue(store.save(newer, response(202, "new"), 4, 200));

        SearchPageStore.Request read = store.beginRequest("public", url);
        SearchPageStore.CachedPage cached = store.load(read, 300);
        assertNotNull(cached);
        assertEquals(4, cached.pageCount);
        assertEquals("new", new JSONObject(cached.payload).getJSONArray("result")
            .getJSONObject(0).getString("english_title"));
        store.finishRequest(older);
        store.finishRequest(newer);
        store.finishRequest(read);
    }

    @Test public void visitedPagesAreKeptStaleUntilCapacityPressure() {
        long now = System.currentTimeMillis();
        store = new SearchPageStore(database,
            new SearchPageStore.Limits(2, 1024 * 1024, 1024));
        save("public", 1, now - SearchPageStore.FRESHNESS_WINDOW_MILLIS - 1, response(301, "stale"));

        SearchPageStore.Request oldPage = store.beginRequest("public", url(1));
        assertNotNull(store.load(oldPage, now));
        store.finishRequest(oldPage);
        assertFalse(SearchPageStore.isFresh(now - SearchPageStore.FRESHNESS_WINDOW_MILLIS, now));

        save("public", 2, now, response(302, "fresh-one"));
        save("public", 3, now + 1, response(303, "fresh-two"));

        assertEquals(2, store.stats().pageCount);
        assertNull(load("public", 1));
        assertNotNull(load("public", 2));
        assertNotNull(load("public", 3));
        assertEquals(1, store.cacheMetrics().pageEvictions);
    }

    @Test public void globalPageAndByteLimitsAreEnforced() {
        store = new SearchPageStore(database, new SearchPageStore.Limits(2, 500, 1024));
        save("account-a", 1, 100, response(401, repeat('a', 64)));
        save("account-a", 2, 200, response(402, repeat('b', 64)));
        save("account-b", 1, 300, response(403, repeat('c', 64)));

        assertTrue(SearchPageStore.cachedPageCount(database) <= 2);
        assertTrue(SearchPageStore.cachedByteCount(database) <= 500);
        assertTrue(store.cacheMetrics().pageEvictions > 0);
    }

    @Test public void hardPageAndPayloadLimitsRejectOversizedPages() {
        store = new SearchPageStore(database, new SearchPageStore.Limits(200, 1024 * 1024, 80));
        SearchPageStore.Request request = store.beginRequest("public", url(1));
        assertFalse(store.save(request, response(501, repeat('x', 512)), 1, 100));
        assertEquals(0, store.stats().pageCount);
        store.finishRequest(request);
    }

    @Test public void clearingInvalidatesPendingWritesAndKeepsAggregateMetrics() {
        SearchPageStore.Request request = store.beginRequest("public", url(1));
        assertNull(store.load(request, 100));
        assertTrue(store.recordRefreshSuccess(request));
        assertTrue(store.save(request, response(601, "title"), 1, 100));

        SearchPageStore.markClearPending();
        assertNull(store.load(request, 200));
        assertFalse(store.save(request, response(602, "late"), 1, 200));
        store.clearAll();

        assertEquals(0, store.stats().pageCount);
        assertEquals(1, store.cacheMetrics().cacheMisses);
        assertEquals(1, store.cacheMetrics().refreshSuccesses);
        assertFalse(store.save(request, response(603, "late"), 1, 300));
        store.finishRequest(request);
    }

    @Test public void configuredQuotaAcceptsOnlySupportedValues() {
        assertEquals(32L * 1024 * 1024, SearchPageStore.Limits.bytesForValue("32"));
        assertEquals(64L * 1024 * 1024, SearchPageStore.Limits.bytesForValue("64"));
        assertEquals(128L * 1024 * 1024, SearchPageStore.Limits.bytesForValue("128"));
        assertEquals(64L * 1024 * 1024, SearchPageStore.Limits.bytesForValue("invalid"));
    }

    private void save(String identity, int page, long syncedAt, String json) {
        SearchPageStore.Request request = store.beginRequest(identity, url(page));
        assertTrue(store.save(request, json, 8, syncedAt));
        store.finishRequest(request);
    }

    private SearchPageStore.CachedPage load(String identity, int page) {
        SearchPageStore.Request request = store.beginRequest(identity, url(page));
        SearchPageStore.CachedPage result = store.load(request, System.currentTimeMillis());
        store.finishRequest(request);
        return result;
    }

    private static String url(int page) {
        return "https://example.test/api/v2/search?query=term&page=" + page;
    }

    private static String response(int id, String title) {
        return "{\"num_pages\":8,\"result\":[{\"id\":" + id
            + ",\"english_title\":\"" + title + "\",\"private_note\":\"not cached\"}]}";
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
