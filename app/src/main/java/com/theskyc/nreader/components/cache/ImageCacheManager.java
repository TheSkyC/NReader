package com.theskyc.nreader.components.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.theskyc.nreader.utility.LogUtility;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageCacheManager {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onComplete(long bytes, @Nullable Exception error);
    }

    private ImageCacheManager() {
    }

    public static void measure(@NonNull Context context, @NonNull Callback callback) {
        Context application = context.getApplicationContext();
        worker.execute(() -> {
            long bytes = 0;
            Exception error = null;
            try {
                bytes = directorySize(imageCacheDirectory(application));
            } catch (IOException | RuntimeException exception) {
                error = exception;
                LogUtility.w("Unable to measure image cache", exception);
            }
            deliver(callback, bytes, error);
        });
    }

    public static void clear(@NonNull Context context, @NonNull Callback callback) {
        Context application = context.getApplicationContext();
        Runnable clearMemory = () -> {
            Exception memoryError = null;
            try {
                Glide.get(application).clearMemory();
            } catch (RuntimeException exception) {
                memoryError = exception;
                LogUtility.w("Unable to clear image memory cache", exception);
            }
            Exception finalMemoryError = memoryError;
            worker.execute(() -> {
                Exception error = finalMemoryError;
                try {
                    Glide.get(application).clearDiskCache();
                } catch (RuntimeException exception) {
                    if (error == null) error = exception;
                    else error.addSuppressed(exception);
                    LogUtility.w("Unable to clear image disk cache", exception);
                }
                long bytes = 0;
                try {
                    bytes = directorySize(imageCacheDirectory(application));
                } catch (IOException | RuntimeException exception) {
                    if (error == null) error = exception;
                    else error.addSuppressed(exception);
                    LogUtility.w("Unable to measure image cache", exception);
                }
                deliver(callback, bytes, error);
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) clearMemory.run();
        else main.post(clearMemory);
    }

    static void clearDiskCache(@NonNull Context context) {
        Glide.get(context.getApplicationContext()).clearDiskCache();
    }

    static File imageCacheDirectory(@NonNull Context context) {
        return CacheDirectories.imageDiskCache(context);
    }

    static long directorySize(@NonNull File root) throws IOException {
        return CacheDirectorySizer.directorySize(root);
    }

    private static void deliver(Callback callback, long bytes, @Nullable Exception error) {
        main.post(() -> callback.onComplete(bytes, error));
    }
}
