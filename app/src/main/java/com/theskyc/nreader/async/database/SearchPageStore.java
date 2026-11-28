// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.Nullable;

import com.theskyc.nreader.R;
import com.theskyc.nreader.components.cache.CacheDirectories;
import com.theskyc.nreader.utility.LogUtility;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stores visited, successfully parsed search pages without retaining query text or response fields. */
public final class SearchPageStore {
    public static final String TABLE_NAME = "SearchPages";
    private static final String METRICS_TABLE_NAME = "SearchPageCacheMetrics";
    private static final int DATABASE_VERSION = 1;
    private static final int PAGE_SCHEMA_VERSION = GalleryPageCachePayload.SCHEMA_VERSION;
    private static final int MAX_ENTRY_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_PAGES = 200;
    private static final long DEFAULT_TOTAL_BYTES = 64L * 1024 * 1024;
    public static final long FRESHNESS_WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    private static final int MAX_TRACKED_REQUESTS = 256;
    private static final String PUBLIC_IDENTITY = "public-search";
    private static final Object REQUEST_LOCK = new Object();
    private static final Object DATABASE_INIT_LOCK = new Object();
    private static final Map<String, RequestState> requests = new LinkedHashMap<>(32, 0.75f, true);
    private static long nextRequestToken;
    private static volatile boolean clearPending;

    private final SQLiteDatabase database;
    private final boolean ownsDatabase;
    @Nullable private final Context applicationContext;
    private Limits limits;

    public SearchPageStore(SQLiteDatabase database) {
        this(database, Limits.DEFAULT, false, null);
    }

    SearchPageStore(SQLiteDatabase database, Limits limits) {
        this(database, limits, false, null);
    }

    SearchPageStore(SQLiteDatabase database, Context context) {
        this(database, Limits.fromPreferences(context), false, context.getApplicationContext());
    }

    private SearchPageStore(SQLiteDatabase database, Limits limits, boolean ownsDatabase,
                            @Nullable Context applicationContext) {
        this.database = database;
        this.limits = limits;
        this.ownsDatabase = ownsDatabase;
        this.applicationContext = applicationContext;
    }

    public static SearchPageStore open(Context context) {
        Context application = context.getApplicationContext();
        File directory = CacheDirectories.searchPageCache(application);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Unable to create search page cache directory");
        File file = new File(directory, "pages.db");
        SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
            SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
        boolean initialized = false;
        try {
            initializeDatabase(database);
            initialized = true;
            return new SearchPageStore(database, Limits.fromPreferences(application), true, application);
        } finally {
            if (!initialized) database.close();
        }
    }

    public void close() {
        if (ownsDatabase && database.isOpen()) database.close();
    }

    public static boolean isFresh(long syncedAt, long nowMillis) {
        return syncedAt > 0 && syncedAt <= nowMillis
            && nowMillis - syncedAt < FRESHNESS_WINDOW_MILLIS;
    }

    public static void createTable(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `" + TABLE_NAME + "` ("
            + "`identity_hash` TEXT NOT NULL,"
            + "`request_hash` TEXT NOT NULL,"
            + "`schema_version` INTEGER NOT NULL,"
            + "`page_count` INTEGER NOT NULL,"
            + "`synced_at` INTEGER NOT NULL,"
            + "`accessed_at` INTEGER NOT NULL,"
            + "`payload_bytes` INTEGER NOT NULL,"
            + "`payload` TEXT NOT NULL,"
            + "`write_token` INTEGER NOT NULL,"
            + "PRIMARY KEY (`identity_hash`, `request_hash`)"
            + ")");
        database.execSQL("CREATE INDEX IF NOT EXISTS `SearchPagesAccess` ON `"
            + TABLE_NAME + "` (`accessed_at`)");
        createMetricsTable(database);
    }

    static void initializeDatabase(SQLiteDatabase database) {
        synchronized (DATABASE_INIT_LOCK) {
            int version = database.getVersion();
            if (version == DATABASE_VERSION) return;
            if (version == 0) database.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
            database.beginTransaction();
            try {
                if (version != 0 && version != DATABASE_VERSION) {
                    database.execSQL("DROP TABLE IF EXISTS `" + TABLE_NAME + "`");
                    database.execSQL("DROP TABLE IF EXISTS `" + METRICS_TABLE_NAME + "`");
                }
                createTable(database);
                database.setVersion(DATABASE_VERSION);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    }

    private static void createMetricsTable(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `" + METRICS_TABLE_NAME + "` ("
            + "`id` INTEGER PRIMARY KEY CHECK (`id`=1),"
            + "`cache_hits` INTEGER NOT NULL DEFAULT 0,"
            + "`cache_misses` INTEGER NOT NULL DEFAULT 0,"
            + "`refresh_successes` INTEGER NOT NULL DEFAULT 0,"
            + "`refresh_failures` INTEGER NOT NULL DEFAULT 0,"
            + "`page_evictions` INTEGER NOT NULL DEFAULT 0"
            + ")");
        database.execSQL("INSERT OR IGNORE INTO `" + METRICS_TABLE_NAME + "` (`id`) VALUES (1)");
    }

    public Request beginRequest(String identity, String requestUrl) {
        String identityHash = sha256(identity == null ? PUBLIC_IDENTITY : identity);
        String requestHash = sha256(requestUrl);
        String requestKey = identityHash + ':' + requestHash;
        synchronized (REQUEST_LOCK) {
            RequestState state = requests.get(requestKey);
            if (state == null) {
                state = new RequestState();
                requests.put(requestKey, state);
            }
            state.latestToken = ++nextRequestToken;
            state.activeRequests++;
            trimRequestStates();
            return new Request(requestKey, identityHash, requestHash, state.latestToken);
        }
    }

    public boolean isCurrent(Request request) {
        synchronized (REQUEST_LOCK) {
            return isCurrentLocked(request);
        }
    }

    public void finishRequest(Request request) {
        synchronized (REQUEST_LOCK) {
            if (request.finished) return;
            request.finished = true;
            RequestState state = requests.get(request.key);
            if (state != null && state.activeRequests > 0) state.activeRequests--;
            trimRequestStates();
        }
    }

    @Nullable
    public CachedPage load(Request request, long nowMillis) {
        synchronized (REQUEST_LOCK) {
            if (!isCurrentLocked(request) || clearPending) return null;
            try (Cursor cursor = database.query(TABLE_NAME,
                new String[]{"schema_version", "page_count", "synced_at", "payload_bytes", "payload"},
                "identity_hash=? AND request_hash=?",
                new String[]{request.identityHash, request.requestHash}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) {
                    incrementMetric("cache_misses");
                    return null;
                }
                String payload = cursor.getString(4);
                int payloadBytes = cursor.getInt(3);
                if (cursor.getInt(0) != PAGE_SCHEMA_VERSION || payload == null
                    || payloadBytes <= 0 || payloadBytes > limits.maxEntryBytes
                    || payload.getBytes(StandardCharsets.UTF_8).length != payloadBytes
                    || !GalleryPageCachePayload.isValid(payload)) {
                    delete(request);
                    incrementMetric("cache_misses");
                    return null;
                }
                ContentValues access = new ContentValues();
                access.put("accessed_at", nowMillis);
                database.update(TABLE_NAME, access, "identity_hash=? AND request_hash=?",
                    new String[]{request.identityHash, request.requestHash});
                return new CachedPage(payload, cursor.getInt(1), cursor.getLong(2));
            } catch (RuntimeException error) {
                LogUtility.w("Unable to read search page cache", error);
                return null;
            }
        }
    }

    public boolean save(Request request, String responseJson, int pageCount, long nowMillis) {
        String payload = GalleryPageCachePayload.create(responseJson, pageCount);
        if (payload == null) return false;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > limits.maxEntryBytes) return false;

        synchronized (REQUEST_LOCK) {
            refreshLimits();
            if (!isCurrentLocked(request) || clearPending) return false;
            boolean ownTransaction = !database.inTransaction();
            if (ownTransaction) database.beginTransactionNonExclusive();
            try {
                ContentValues values = new ContentValues();
                values.put("identity_hash", request.identityHash);
                values.put("request_hash", request.requestHash);
                values.put("schema_version", PAGE_SCHEMA_VERSION);
                values.put("page_count", Math.max(0, pageCount));
                values.put("synced_at", nowMillis);
                values.put("accessed_at", nowMillis);
                values.put("payload_bytes", bytes.length);
                values.put("payload", payload);
                values.put("write_token", request.token);
                if (database.insertWithOnConflict(TABLE_NAME, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) < 0) return false;
                trimGlobal(nowMillis);
                if (ownTransaction) database.setTransactionSuccessful();
                return true;
            } catch (RuntimeException error) {
                LogUtility.w("Unable to save search page cache", error);
                return false;
            } finally {
                if (ownTransaction) database.endTransaction();
            }
        }
    }

    public boolean delete(Request request) {
        synchronized (REQUEST_LOCK) {
            if (!isCurrentLocked(request) || clearPending) return false;
            return database.delete(TABLE_NAME, "identity_hash=? AND request_hash=?",
                new String[]{request.identityHash, request.requestHash}) > 0;
        }
    }

    public void trimToLimits() {
        synchronized (REQUEST_LOCK) {
            refreshLimits();
            boolean ownTransaction = !database.inTransaction();
            if (ownTransaction) database.beginTransactionNonExclusive();
            try {
                trimGlobal(System.currentTimeMillis());
                if (ownTransaction) database.setTransactionSuccessful();
            } finally {
                if (ownTransaction) database.endTransaction();
            }
        }
        compactAfterMaintenance();
    }

    public void compactAfterMaintenance() {
        synchronized (REQUEST_LOCK) {
            compact();
        }
    }

    public PageStats stats() {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*), COALESCE(SUM(payload_bytes), 0), "
            + "COALESCE(MAX(synced_at), 0) FROM " + TABLE_NAME, null)) {
            if (!cursor.moveToFirst()) return new PageStats(0, 0, 0);
            return new PageStats(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2));
        }
    }

    public CacheMetrics cacheMetrics() {
        try (Cursor cursor = database.rawQuery("SELECT cache_hits, cache_misses, refresh_successes, "
            + "refresh_failures, page_evictions FROM " + METRICS_TABLE_NAME + " WHERE id=1", null)) {
            if (!cursor.moveToFirst()) return CacheMetrics.EMPTY;
            return new CacheMetrics(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
                cursor.getLong(3), cursor.getLong(4));
        }
    }

    public boolean recordCacheHit(Request request) {
        return recordRequestMetric(request, "cache_hits");
    }

    public boolean recordCacheMiss(Request request) {
        return recordRequestMetric(request, "cache_misses");
    }

    public boolean recordRefreshSuccess(Request request) {
        return recordRequestMetric(request, "refresh_successes");
    }

    public boolean recordRefreshFailure(Request request) {
        return recordRequestMetric(request, "refresh_failures");
    }

    public static int cachedPageCount(SQLiteDatabase database) {
        return (int) DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM " + TABLE_NAME, null);
    }

    public static long cachedByteCount(SQLiteDatabase database) {
        return DatabaseUtils.longForQuery(database,
            "SELECT COALESCE(SUM(payload_bytes), 0) FROM " + TABLE_NAME, null);
    }

    public static void invalidateAll() {
        synchronized (REQUEST_LOCK) {
            clearPending = true;
            for (RequestState state : requests.values()) state.latestToken = ++nextRequestToken;
        }
    }

    public static void markClearPending() {
        clearPending = true;
    }

    public static void cancelPendingClear() {
        synchronized (REQUEST_LOCK) {
            clearPending = false;
        }
    }

    public void clearAll() {
        invalidateAll();
        synchronized (REQUEST_LOCK) {
            boolean ownTransaction = !database.inTransaction();
            try {
                if (ownTransaction) database.beginTransactionNonExclusive();
                database.delete(TABLE_NAME, null, null);
                if (ownTransaction) database.setTransactionSuccessful();
            } finally {
                try {
                    if (ownTransaction && database.inTransaction()) database.endTransaction();
                } finally {
                    clearPending = false;
                }
            }
        }
        compactAfterMaintenance();
    }

    private void trimGlobal(long nowMillis) {
        while (cachedPageCount(database) > limits.maxTotalPages
            || cachedByteCount(database) > limits.maxTotalBytes) {
            String identityHash;
            String requestHash;
            try (Cursor cursor = database.rawQuery("SELECT identity_hash, request_hash FROM " + TABLE_NAME
                + " ORDER BY CASE WHEN synced_at>? AND synced_at<=? THEN 1 ELSE 0 END, "
                + "accessed_at ASC, rowid ASC LIMIT 1",
                new String[]{Long.toString(nowMillis - FRESHNESS_WINDOW_MILLIS), Long.toString(nowMillis)})) {
                if (!cursor.moveToFirst()) return;
                identityHash = cursor.getString(0);
                requestHash = cursor.getString(1);
            }
            if (database.delete(TABLE_NAME, "identity_hash=? AND request_hash=?",
                new String[]{identityHash, requestHash}) > 0) incrementMetric("page_evictions");
        }
    }

    private boolean recordRequestMetric(Request request, String column) {
        synchronized (REQUEST_LOCK) {
            if (!isCurrentLocked(request) || clearPending) return false;
            incrementMetric(column);
            return true;
        }
    }

    private void incrementMetric(String column) {
        synchronized (REQUEST_LOCK) {
            try {
                database.execSQL("UPDATE `" + METRICS_TABLE_NAME + "` SET `" + column + "`="
                    + "CASE WHEN `" + column + "` < ? THEN `" + column + "` + 1 ELSE `" + column + "` END "
                    + "WHERE `id`=1", new Object[]{Long.MAX_VALUE});
            } catch (RuntimeException error) {
                LogUtility.w("Unable to update search page cache metrics", error);
            }
        }
    }

    private boolean isCurrentLocked(Request request) {
        RequestState state = requests.get(request.key);
        return state != null && state.latestToken == request.token;
    }

    private void refreshLimits() {
        if (applicationContext != null) limits = Limits.fromPreferences(applicationContext);
    }

    private void compact() {
        if (database.inTransaction()) return;
        try {
            database.execSQL("PRAGMA incremental_vacuum(32)");
        } catch (RuntimeException error) {
            LogUtility.w("Unable to compact search page cache", error);
        }
    }

    private static void trimRequestStates() {
        while (requests.size() > MAX_TRACKED_REQUESTS) {
            String removable = null;
            for (Map.Entry<String, RequestState> entry : requests.entrySet()) {
                if (entry.getValue().activeRequests == 0) {
                    removable = entry.getKey();
                    break;
                }
            }
            if (removable == null) return;
            requests.remove(removable);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", valueByte & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static final class Request {
        private final String key;
        private final String identityHash;
        private final String requestHash;
        private final long token;
        private boolean finished;

        private Request(String key, String identityHash, String requestHash, long token) {
            this.key = key;
            this.identityHash = identityHash;
            this.requestHash = requestHash;
            this.token = token;
        }
    }

    public static final class CachedPage {
        public final String payload;
        public final int pageCount;
        public final long syncedAt;

        CachedPage(String payload, int pageCount, long syncedAt) {
            this.payload = payload;
            this.pageCount = pageCount;
            this.syncedAt = syncedAt;
        }
    }

    public static final class PageStats {
        public final int pageCount;
        public final long bytes;
        public final long lastSyncedAt;

        PageStats(int pageCount, long bytes, long lastSyncedAt) {
            this.pageCount = pageCount;
            this.bytes = bytes;
            this.lastSyncedAt = lastSyncedAt;
        }
    }

    public static final class CacheMetrics {
        private static final CacheMetrics EMPTY = new CacheMetrics(0, 0, 0, 0, 0);
        public final long cacheHits;
        public final long cacheMisses;
        public final long refreshSuccesses;
        public final long refreshFailures;
        public final long pageEvictions;

        CacheMetrics(long cacheHits, long cacheMisses, long refreshSuccesses,
                     long refreshFailures, long pageEvictions) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.refreshSuccesses = refreshSuccesses;
            this.refreshFailures = refreshFailures;
            this.pageEvictions = pageEvictions;
        }
    }

    static final class Limits {
        static final Limits DEFAULT = new Limits(MAX_TOTAL_PAGES, DEFAULT_TOTAL_BYTES, MAX_ENTRY_BYTES);
        final int maxTotalPages;
        final long maxTotalBytes;
        final int maxEntryBytes;

        Limits(int maxTotalPages, long maxTotalBytes, int maxEntryBytes) {
            this.maxTotalPages = maxTotalPages;
            this.maxTotalBytes = maxTotalBytes;
            this.maxEntryBytes = maxEntryBytes;
        }

        static Limits fromPreferences(Context context) {
            SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
            String megabytes;
            try {
                megabytes = preferences.getString(context.getString(R.string.preference_key_search_page_quota), "64");
            } catch (ClassCastException error) {
                megabytes = "64";
            }
            return new Limits(MAX_TOTAL_PAGES, bytesForValue(megabytes), MAX_ENTRY_BYTES);
        }

        static long bytesForValue(@Nullable String value) {
            if ("32".equals(value)) return 32L * 1024 * 1024;
            if ("128".equals(value)) return 128L * 1024 * 1024;
            return DEFAULT_TOTAL_BYTES;
        }
    }

    private static final class RequestState {
        long latestToken;
        int activeRequests;

        RequestState() {
        }
    }
}
