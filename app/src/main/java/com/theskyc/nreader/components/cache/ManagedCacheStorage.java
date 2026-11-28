// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.async.database.OnlineFavoritePageStore;
import com.theskyc.nreader.async.database.SearchPageStore;
import com.theskyc.nreader.components.reader.ContinuousPageAdapter;
import com.theskyc.nreader.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Measures only explicitly registered, regenerable cache directories. */
public final class ManagedCacheStorage {
    public static final long UNAVAILABLE = -1L;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onComplete(@NonNull Usage usage);
    }

    public interface ClearCallback {
        void onComplete(@NonNull ClearResult result);
    }

    public static final class ClearResult {
        public final long imageCacheBytes;
        @Nullable public final Exception imageError;
        public final boolean favoritePagesCleared;
        public final boolean browsePagesCleared;
        public final boolean readerSnapshotsCleared;
        public final boolean readerSnapshotsSkipped;

        ClearResult(long imageCacheBytes, @Nullable Exception imageError, boolean favoritePagesCleared,
                    boolean browsePagesCleared, boolean readerSnapshotsCleared, boolean readerSnapshotsSkipped) {
            this.imageCacheBytes = imageCacheBytes;
            this.imageError = imageError;
            this.favoritePagesCleared = favoritePagesCleared;
            this.browsePagesCleared = browsePagesCleared;
            this.readerSnapshotsCleared = readerSnapshotsCleared;
            this.readerSnapshotsSkipped = readerSnapshotsSkipped;
        }
    }

    public static final class Usage {
        public final long imageBytes;
        public final long favoritePageBytes;
        public final long searchPageBytes;
        public final long readerSnapshotBytes;

        Usage(long imageBytes, long favoritePageBytes, long searchPageBytes, long readerSnapshotBytes) {
            this.imageBytes = imageBytes;
            this.favoritePageBytes = favoritePageBytes;
            this.searchPageBytes = searchPageBytes;
            this.readerSnapshotBytes = readerSnapshotBytes;
        }

        public long totalBytes() {
            if (imageBytes < 0 || favoritePageBytes < 0 || searchPageBytes < 0 || readerSnapshotBytes < 0)
                return UNAVAILABLE;
            long total = saturatedAdd(imageBytes, favoritePageBytes);
            total = saturatedAdd(total, searchPageBytes);
            return saturatedAdd(total, readerSnapshotBytes);
        }
    }

    private ManagedCacheStorage() {
    }

    public static void measure(@NonNull Context context, @NonNull Callback callback) {
        File cacheDirectory = context.getApplicationContext().getCacheDir();
        Handler main = new Handler(Looper.getMainLooper());
        worker.execute(() -> {
            Usage usage = measure(cacheDirectory);
            main.post(() -> callback.onComplete(usage));
        });
    }

    public static void clearAll(@NonNull Context context, @NonNull ClearCallback callback) {
        Context application = context.getApplicationContext();
        SearchPageStore.markClearPending();
        ImageCacheManager.clear(application, (bytes, imageError) -> worker.execute(() -> {
            boolean browsePagesCleared = clearBrowsePages(application);
            boolean favoritePagesCleared = clearFavoritePages(application);
            boolean readerSnapshotsCleared = false;
            boolean readerSnapshotsSkipped = false;
            try {
                readerSnapshotsCleared = ContinuousPageAdapter.clearIdleSnapshots(application);
                readerSnapshotsSkipped = !readerSnapshotsCleared;
            } catch (IOException | RuntimeException error) {
                LogUtility.w("Unable to clear reader snapshot cache", error);
            }
            ClearResult result = new ClearResult(bytes, imageError, favoritePagesCleared,
                browsePagesCleared, readerSnapshotsCleared, readerSnapshotsSkipped);
            new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(result));
        }));
    }

    private static boolean clearBrowsePages(Context application) {
        SearchPageStore store = null;
        RuntimeException failure = null;
        try {
            store = SearchPageStore.open(application);
            store.clearAll();
        } catch (RuntimeException error) {
            failure = error;
            LogUtility.w("Unable to clear browse page cache", error);
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                    LogUtility.w("Unable to close browse page cache", error);
                }
            } else {
                SearchPageStore.cancelPendingClear();
            }
        }
        return failure == null;
    }

    private static boolean clearFavoritePages(Context application) {
        OnlineFavoritePageStore store = null;
        RuntimeException failure = null;
        try {
            store = OnlineFavoritePageStore.open(application);
            store.clearAll();
        } catch (RuntimeException error) {
            failure = error;
            LogUtility.w("Unable to clear favorite page cache", error);
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                    LogUtility.w("Unable to close favorite page cache", error);
                }
            }
        }
        return failure == null;
    }

    static Usage measure(@NonNull File cacheDirectory) {
        return new Usage(
            measureDirectory(CacheDirectories.imageDiskCache(cacheDirectory), "image"),
            measureDirectory(CacheDirectories.favoritePageCache(cacheDirectory), "favorite pages"),
            measureDirectory(CacheDirectories.searchPageCache(cacheDirectory), "search pages"),
            measureDirectory(CacheDirectories.readerTileSnapshots(cacheDirectory), "reader snapshots"));
    }

    private static long measureDirectory(File directory, String category) {
        try {
            return CacheDirectorySizer.directorySize(directory);
        } catch (IOException | RuntimeException error) {
            LogUtility.w("Unable to measure managed cache category: " + category, error);
            return UNAVAILABLE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
