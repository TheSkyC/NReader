// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

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

    public static final class Usage {
        public final long imageBytes;
        public final long favoritePageBytes;
        public final long readerSnapshotBytes;

        Usage(long imageBytes, long favoritePageBytes, long readerSnapshotBytes) {
            this.imageBytes = imageBytes;
            this.favoritePageBytes = favoritePageBytes;
            this.readerSnapshotBytes = readerSnapshotBytes;
        }

        public long totalBytes() {
            if (imageBytes < 0 || favoritePageBytes < 0 || readerSnapshotBytes < 0) return UNAVAILABLE;
            long total = saturatedAdd(imageBytes, favoritePageBytes);
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

    static Usage measure(@NonNull File cacheDirectory) {
        return new Usage(
            measureDirectory(CacheDirectories.imageDiskCache(cacheDirectory), "image"),
            measureDirectory(CacheDirectories.favoritePageCache(cacheDirectory), "favorite pages"),
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
