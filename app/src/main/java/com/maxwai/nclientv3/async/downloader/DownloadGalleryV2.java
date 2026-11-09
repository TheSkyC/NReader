// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class DownloadGalleryV2 extends Worker {
    private static final ReentrantLock lock = new ReentrantLock();
    private volatile GalleryDownloaderV2 currentDownload;

    @Override
    public void onStopped() {
        GalleryDownloaderV2 downloader = currentDownload;
        if (downloader != null) downloader.setStatus(GalleryDownloaderV2.Status.PAUSED);
    }

    public DownloadGalleryV2(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void downloadGallery(Context context, GenericGallery gallery) {
        if (gallery.isValid() && gallery instanceof Gallery)
        {
            downloadGallery(context, (Gallery) gallery);
            return;
        }
        if (gallery.getId() > 0) {
            if (gallery instanceof SimpleGallery) {
                SimpleGallery simple = (SimpleGallery) gallery;
                downloadGallery(context, gallery.getTitle(), simple.getThumbnail(), simple.getId());
            } else downloadGallery(context, null, null, gallery.getId());
        }
    }

    private static void downloadGallery(Context context, String title, Uri thumbnail, int id) {
        if (id < 1) return;
        enqueue(context, new GalleryDownloaderManager(context, title, thumbnail, id));
    }

    private static void downloadGallery(Context context, Gallery gallery) {
        downloadGallery(context, gallery, 0, gallery.getPageCount() - 1);
    }

    private static void downloadGallery(Context context, Gallery gallery, int start, int end) {
        enqueue(context, new GalleryDownloaderManager(context, gallery, start, end));
    }

    private static void enqueue(Context context, GalleryDownloaderManager manager) {
        if (DownloadQueue.add(manager)) startWork(context);
        else new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            android.widget.Toast.makeText(context, com.maxwai.nclientv3.R.string.download_conflicting_task,
                android.widget.Toast.LENGTH_LONG).show());
    }

    public static void loadDownloads(Context context) {
        List<GalleryDownloaderManager> g = Queries.DownloadTable.getAllDownloads(context);
        for (GalleryDownloaderManager gg : g) {
            gg.downloader().setStatus(GalleryDownloaderV2.Status.PAUSED);
            DownloadQueue.add(gg);
        }
        startWork(context);
    }

    public static void downloadRange(Context context, Gallery gallery, int start, int end) {
        downloadGallery(context, gallery, start, end);
    }

    public static void startWork(@Nullable Context context) {
        if (context != null) {
            WorkRequest DownloadGalleryWorkRequest = new OneTimeWorkRequest.Builder(DownloadGalleryV2.class).build();
            WorkManager.getInstance(context).enqueue(DownloadGalleryWorkRequest);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.success();
        }
        try {
            GalleryDownloaderManager entry = DownloadQueue.fetch();
            while (entry != null && !isStopped()) {
                LogUtility.d("Downloading: " + entry.downloader().getId());
                currentDownload = entry.downloader();
                if (isStopped()) break;
                currentDownload.runDownload();
                currentDownload = null;
                entry = DownloadQueue.fetch();
            }
        } finally {
            currentDownload = null;
            lock.unlock();
        }
        return Result.success();
    }



}
