package com.theskyc.nreader.async.database;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.R;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineFavoritePageStoreTest {
    private SQLiteDatabase database;
    private OnlineFavoritePageStore store;

    @Before public void setUp() {
        database = SQLiteDatabase.create(null);
        OnlineFavoritePageStore.createTable(database);
        store = new OnlineFavoritePageStore(database);
    }

    @After public void tearDown() {
        database.close();
    }

    @Test public void cacheIsScopedToIdentityAndCompleteRequestUrl() throws Exception {
        String identity = "account-a";
        String url = "https://nhentai.net/api/v2/favorites?page=2&q=artist";
        OnlineFavoritePageStore.Request write = store.beginRequest(identity, url);
        assertTrue(store.save(write, response(201, "cached"), 5, 100));
        store.finishRequest(write);

        OnlineFavoritePageStore.Request sameAccount = store.beginRequest(identity, url);
        OnlineFavoritePageStore.CachedPage cached = store.load(sameAccount, 200);
        assertNotNull(cached);
        assertEquals(5, cached.pageCount);
        assertEquals(100, cached.syncedAt);
        store.finishRequest(sameAccount);

        OnlineFavoritePageStore.Request otherAccount = store.beginRequest("account-b", url);
        assertNull(store.load(otherAccount, 300));
        store.finishRequest(otherAccount);

        OnlineFavoritePageStore.Request otherQuery = store.beginRequest(identity,
            "https://nhentai.net/api/v2/favorites?page=2&q=group");
        assertNull(store.load(otherQuery, 400));
        store.finishRequest(otherQuery);
    }

    @Test public void metricsPersistAsAggregatedCacheActivity() {
        OnlineFavoritePageStore.Request miss = store.beginRequest("account-a",
            "https://example.test/favorites?page=1");
        assertNull(store.load(miss, 100));
        store.finishRequest(miss);
        assertTrue(store.recordCacheHit(miss));
        assertTrue(store.recordRefreshSuccess(miss));
        assertTrue(store.recordRefreshFailure(miss));

        store = new OnlineFavoritePageStore(database);
        OnlineFavoritePageStore.CacheMetrics metrics = store.cacheMetrics();
        assertEquals(1, metrics.cacheHits);
        assertEquals(1, metrics.cacheMisses);
        assertEquals(1, metrics.refreshSuccesses);
        assertEquals(1, metrics.refreshFailures);
        assertEquals(0, metrics.pageEvictions);

        OnlineFavoritePageStore.Request stale = store.beginRequest("account-a",
            "https://example.test/favorites?page=2");
        OnlineFavoritePageStore.Request current = store.beginRequest("account-a",
            "https://example.test/favorites?page=2");
        assertFalse(store.recordRefreshFailure(stale));
        assertEquals(1, store.cacheMetrics().refreshFailures);
        store.finishRequest(stale);
        store.finishRequest(current);
    }

    @Test public void databaseUpgradePreservesPagesAndAddsMetrics() throws Exception {
        String identity = "upgrade-account";
        String url = "https://example.test/favorites?page=4";
        OnlineFavoritePageStore.Request write = store.beginRequest(identity, url);
        assertTrue(store.save(write, response(204, "preserved"), 6, 100));
        store.finishRequest(write);
        database.execSQL("DROP TABLE IF EXISTS `OnlineFavoritePageCacheMetrics`");
        database.setVersion(1);

        OnlineFavoritePageStore.initializeDatabase(database);

        assertEquals(2, database.getVersion());
        OnlineFavoritePageStore.Request read = store.beginRequest(identity, url);
        OnlineFavoritePageStore.CachedPage cached = store.load(read, 200);
        assertNotNull(cached);
        assertEquals("preserved", new JSONObject(cached.payload)
            .getJSONArray("result").getJSONObject(0).getString("english_title"));
        assertEquals(0, store.cacheMetrics().cacheHits);
        assertEquals(0, store.cacheMetrics().cacheMisses);
        store.finishRequest(read);
    }

    @Test public void lateOlderResponseCannotReplaceNewerPage() throws Exception {
        String url = "https://nhentai.net/api/v2/favorites?page=1";
        OnlineFavoritePageStore.Request older = store.beginRequest("account", url);
        OnlineFavoritePageStore.Request newer = store.beginRequest("account", url);

        assertFalse(store.save(older, response(202, "old"), 3, 100));
        assertTrue(store.save(newer, response(203, "new"), 4, 200));
        store.finishRequest(older);
        store.finishRequest(newer);

        OnlineFavoritePageStore.Request read = store.beginRequest("account", url);
        OnlineFavoritePageStore.CachedPage cached = store.load(read, 300);
        assertNotNull(cached);
        assertEquals(4, cached.pageCount);
        JSONObject page = new JSONObject(cached.payload);
        assertEquals("new", page.getJSONArray("result").getJSONObject(0).getString("english_title"));
        store.finishRequest(read);
    }

    @Test public void cacheKeepsOnlyRequiredCardFields() throws Exception {
        String input = "{\"num_pages\":2,\"user\":\"private\",\"result\":[{"
            + "\"id\":204,\"media_id\":\"77\",\"thumbnail\":\"g/77.jpg\","
            + "\"english_title\":\"Title\",\"japanese_title\":\"題名\",\"num_pages\":12,"
            + "\"tag_ids\":[3],\"private_note\":\"do-not-cache\"}]}";
        OnlineFavoritePageStore.Request request = store.beginRequest("account", "https://example.test/favorites");
        assertTrue(store.save(request, input, 2, 100));
        OnlineFavoritePageStore.CachedPage cached = store.load(request, 200);
        assertNotNull(cached);
        assertFalse(cached.payload.contains("private"));
        assertFalse(cached.payload.contains("do-not-cache"));
        JSONObject item = new JSONObject(cached.payload).getJSONArray("result").getJSONObject(0);
        assertEquals(204, item.getInt("id"));
        assertEquals("Title", item.getString("english_title"));
        store.finishRequest(request);
    }

    @Test public void accountPageAndGlobalByteLimitsEvictLeastRecentlyUsedRows() {
        OnlineFavoritePageStore.Limits limits = new OnlineFavoritePageStore.Limits(2, 10, 450, 1024);
        store = new OnlineFavoritePageStore(database, limits);
        for (int page = 1; page <= 3; page++) {
            save("account-a", page, 100L * page, response(300 + page, repeat('a', 64)));
        }

        assertEquals(2, store.stats("account-a").pageCount);
        assertTrue(store.stats("account-a").bytes <= 450);

        save("account-b", 1, 400, response(400, repeat('b', 64)));
        assertTrue(OnlineFavoritePageStore.cachedPageCount(database) <= 10);
        assertTrue(OnlineFavoritePageStore.cachedByteCount(database) <= 450);
        assertTrue(store.stats("account-a").pageCount + store.stats("account-b").pageCount <= 2);
        assertTrue(store.cacheMetrics().pageEvictions > 0);
    }

    @Test public void applyingLowerAccountLimitTrimsEveryAccount() {
        for (int page = 1; page <= 3; page++) {
            save("account-a", page, 100L * page, response(600 + page, "a" + page));
        }
        for (int page = 1; page <= 2; page++) {
            save("account-b", page, 100L * page, response(700 + page, "b" + page));
        }

        store = new OnlineFavoritePageStore(database,
            new OnlineFavoritePageStore.Limits(1, 10, 1024 * 1024, 1024));
        store.trimToLimits();

        assertEquals(1, store.stats("account-a").pageCount);
        assertEquals(1, store.stats("account-b").pageCount);
    }

    @Test public void configuredLimitsAcceptOnlySupportedValues() {
        assertEquals(20, OnlineFavoritePageStore.Limits.accountPagesForValue("20"));
        assertEquals(35, OnlineFavoritePageStore.Limits.accountPagesForValue("35"));
        assertEquals(50, OnlineFavoritePageStore.Limits.accountPagesForValue("invalid"));
        assertEquals(8L * 1024 * 1024, OnlineFavoritePageStore.Limits.bytesForValue("8"));
        assertEquals(16L * 1024 * 1024, OnlineFavoritePageStore.Limits.bytesForValue("16"));
        assertEquals(32L * 1024 * 1024, OnlineFavoritePageStore.Limits.bytesForValue("32"));
        assertEquals(16L * 1024 * 1024, OnlineFavoritePageStore.Limits.bytesForValue("invalid"));
    }

    @Test public void inFlightWriterUsesUpdatedQuotaBeforeSaving() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences settings = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        settings.edit()
            .putString(context.getString(R.string.preference_key_online_favorite_page_limit), "50")
            .putString(context.getString(R.string.preference_key_online_favorite_page_quota), "16")
            .commit();
        store = new OnlineFavoritePageStore(database, context);

        for (int page = 1; page <= 21; page++) {
            save("quota-race-account", page, page * 100L, response(800 + page, "title"));
        }
        settings.edit()
            .putString(context.getString(R.string.preference_key_online_favorite_page_limit), "20")
            .commit();
        save("quota-race-account", 22, 2200, response(822, "latest"));

        assertEquals(20, store.stats("quota-race-account").pageCount);
    }

    @Test public void clearingAccountInvalidatesPendingWritersAndKeepsOtherAccounts() throws Exception {
        String url = "https://example.test/favorites?page=1";
        OnlineFavoritePageStore.Request pending = store.beginRequest("account-a", url);
        save("account-b", 1, 100, response(500, "other"));

        store.clearAccount("account-a");
        assertFalse(store.save(pending, response(501, "late"), 1, 200));
        assertEquals(0, store.stats("account-a").pageCount);
        assertEquals(1, store.stats("account-b").pageCount);
        store.finishRequest(pending);
    }

    @Test public void pendingInvalidationBlocksStaleReadsAndWritesUntilRowsAreCleared() {
        String identity = "account-pending-clear";
        String url = "https://example.test/favorites?page=1";
        save(identity, 1, 100, response(901, "stale"));

        OnlineFavoritePageStore.invalidateAccount(identity);
        OnlineFavoritePageStore.Request duringClear = store.beginRequest(identity, url);
        assertNull(store.load(duringClear, 200));
        assertFalse(store.save(duringClear, response(902, "early"), 1, 200));
        store.finishRequest(duringClear);

        store.clearInvalidatedAccount(identity);
        OnlineFavoritePageStore.Request afterClear = store.beginRequest(identity, url);
        assertNull(store.load(afterClear, 300));
        assertTrue(store.save(afterClear, response(903, "fresh"), 1, 300));
        assertNotNull(store.load(afterClear, 400));
        store.finishRequest(afterClear);
    }

    private void save(String identity, int page, long time, String response) {
        OnlineFavoritePageStore.Request request = store.beginRequest(identity,
            "https://example.test/favorites?page=" + page);
        assertTrue(store.save(request, response, 8, time));
        store.finishRequest(request);
    }

    private static String response(int id, String title) {
        return "{\"result\":[{\"id\":" + id + ",\"media_id\":\"1\","
            + "\"english_title\":\"" + title + "\",\"thumbnail\":\"g/1.jpg\"}]}";
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
