// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;

/** Lists cache directories with explicit ownership and safe regeneration boundaries. */
public final class CacheDirectories {
    private static final String IMAGE_DISK_CACHE = "image_manager_disk_cache";
    private static final String FAVORITE_PAGE_CACHE = "online_favorite_pages";
    private static final String READER_TILE_SNAPSHOTS = "reader-tile-snapshots";

    private CacheDirectories() {
    }

    @NonNull
    public static File imageDiskCache(@NonNull Context context) {
        return imageDiskCache(context.getApplicationContext().getCacheDir());
    }

    @NonNull
    static File imageDiskCache(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, IMAGE_DISK_CACHE);
    }

    @NonNull
    public static File favoritePageCache(@NonNull Context context) {
        return favoritePageCache(context.getApplicationContext().getCacheDir());
    }

    @NonNull
    static File favoritePageCache(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, FAVORITE_PAGE_CACHE);
    }

    @NonNull
    public static File readerTileSnapshots(@NonNull Context context) {
        return readerTileSnapshots(context.getApplicationContext().getCacheDir());
    }

    @NonNull
    public static File readerTileSnapshots(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, READER_TILE_SNAPSHOTS);
    }
}
