// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.components.status.StatusManager;
import com.maxwai.nclientv3.files.LocalDownloadState;
import com.maxwai.nclientv3.settings.Database;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GalleryStateRepository {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicLong generation = new AtomicLong();
    private static final AtomicLong localGeneration = new AtomicLong();
    private static final LruCache<String, GalleryState> cache = new LruCache<>(128);
    private static final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private static final Map<Integer, List<File>> folders = new HashMap<>();
    private static long foldersGeneration = -1;
    private static boolean storageUncertain;
    private static final Pattern idMarker = Pattern.compile("\\.\\d{1,9}");
    private static final Runnable notifyListeners = () -> {
        listeners.removeIf(reference -> reference.get() == null);
        for (WeakReference<Runnable> reference : new ArrayList<>(listeners)) {
            Runnable listener = reference.get();
            if (listener != null) listener.run();
        }
    };

    private GalleryStateRepository() {}

    public static void observe(Runnable listener) {
        listeners.add(new WeakReference<>(listener));
    }

    public static void removeObserver(Runnable listener) {
        listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
    }

    public static void invalidate() {
        generation.incrementAndGet();
        main.removeCallbacks(notifyListeners);
        main.postDelayed(notifyListeners, 100);
    }

    public static void invalidateLocalFiles() {
        localGeneration.incrementAndGet();
        invalidate();
    }

    public static void load(Context context, int id, File directory, Consumer<GalleryState> result) {
        Context application = context.getApplicationContext();
        long requestedGeneration = generation.get();
        String key = requestedGeneration + ":" + id + ":" + (directory == null ? "" : directory.getAbsolutePath());
        executor.execute(() -> {
            if (generation.get() != requestedGeneration) return;
            GalleryState state = cache.get(key);
            if (state == null) {
                try {
                    long localVersion = localGeneration.get();
                    if (foldersGeneration != localVersion) indexFolders(application, localVersion);
                    state = resolve(application, id, directory);
                    cache.put(key, state);
                } catch (RuntimeException exception) {
                    LogUtility.w("Unable to resolve gallery state", exception);
                    return;
                }
            }
            GalleryState resolved = state;
            main.post(() -> {
                if (generation.get() == requestedGeneration) result.accept(resolved);
            });
        });
    }

    private static GalleryState resolve(Context context, int id, File directory) {
        boolean favorite = false;
        String name = null;
        int color = 0;
        ReadingPosition position = null;
        if (id > 0) {
            favorite = Queries.FavoriteTable.isFavorite(id);
            position = Queries.ReadingProgressTable.fromId(id);
            try (Cursor cursor = Database.getDatabase().rawQuery(
                "SELECT s.name, s.color FROM StatusManga m JOIN Status s ON m.name=s.name WHERE m.gallery=?",
                new String[]{Integer.toString(id)})) {
                if (cursor.moveToFirst() && !StatusManager.DEFAULT_STATUS.equals(cursor.getString(0))) {
                    name = cursor.getString(0);
                    color = cursor.getInt(1);
                }
            }
        }
        LocalDownloadState local = storageUncertain ? LocalDownloadState.UNVERIFIED : LocalDownloadState.ABSENT;
        List<File> candidates = directory == null ? folders.get(id) : java.util.Collections.singletonList(directory);
        int expectedPages = 0;
        if (candidates != null) {
            local = LocalDownloadState.UNVERIFIED;
            for (File candidate : candidates) {
                LocalDownloadState inspected = LocalDownloadState.inspect(candidate);
                int candidateTotal = LocalDownloadState.expectedPages(candidate);
                if (expectedPages == 0) expectedPages = candidateTotal;
                if (inspected == LocalDownloadState.COMPLETE) {
                    local = inspected;
                    expectedPages = candidateTotal;
                    break;
                }
                if (inspected != LocalDownloadState.UNVERIFIED) local = inspected;
            }
        }
        return new GalleryState(favorite, name, color, position, local, expectedPages,
            candidates != null && !candidates.isEmpty());
    }

    private static void indexFolders(Context context, long version) {
        folders.clear();
        storageUncertain = !Global.hasStoragePermission(context) || Global.DOWNLOADFOLDER == null;
        Set<File> roots = new HashSet<>();
        if (Global.DOWNLOADFOLDER != null) roots.add(Global.DOWNLOADFOLDER);
        for (File parent : Global.getUsableFolders(context)) roots.add(new File(parent, Global.MAINFOLDER_NAME + "/Download"));
        for (File root : roots) {
            if (!root.exists()) {
                if (root.equals(Global.DOWNLOADFOLDER)) storageUncertain = true;
                continue;
            }
            File[] directories = root.listFiles(File::isDirectory);
            if (directories == null) {
                storageUncertain = true;
                continue;
            }
            for (File directory : directories) {
                File[] markers = directory.listFiles((parent, name) -> idMarker.matcher(name).matches());
                if (markers == null) {
                    storageUncertain = true;
                    continue;
                }
                for (File marker : markers) {
                    int id = Integer.parseInt(marker.getName().substring(1));
                    if (id > 0) folders.computeIfAbsent(id, ignored -> new ArrayList<>()).add(directory);
                }
            }
        }
        foldersGeneration = version;
    }
}
