// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import android.content.Context;
import com.maxwai.nclientv3.async.database.DownloadTaskStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DownloadQueue {
    private static final List<GalleryDownloaderManager> downloadQueue = new CopyOnWriteArrayList<>();

    public static synchronized boolean add(GalleryDownloaderManager x) {
        for (GalleryDownloaderManager manager : downloadQueue)
            if (x.downloader().getId() == manager.downloader().getId()) {
                if (!manager.downloader().coversRequest(x.downloader())) return false;
                manager.downloader().retry();
                givePriority(manager.downloader());
                return true;
            }
        x.downloader().enablePersistence();
        if (!x.downloader().persistNow()) throw new IllegalStateException("Unable to persist queued download");
        downloadQueue.add(x);
        return true;
    }

    public static synchronized void addRestored(GalleryDownloaderManager task) {
        for (GalleryDownloaderManager current : downloadQueue)
            if (current.downloader().getId() == task.downloader().getId()) return;
        downloadQueue.add(task);
    }

    public static synchronized void replaceAfterRestore(Context context) {
        for (GalleryDownloaderManager task : downloadQueue) task.downloader().abandon();
        downloadQueue.clear();
        if (context != null) DownloadGalleryV2.loadDownloads(context);
    }

    public static GalleryDownloaderV2 fetchForData() {
        for (GalleryDownloaderManager x : downloadQueue)
            if (!x.downloader().hasData() && x.downloader().canBeFetched()) return x.downloader();
        return null;
    }

    public static GalleryDownloaderManager fetch() {
        for (GalleryDownloaderManager x : downloadQueue)
            if (x.downloader().canBeFetched()) return x;
        return null;
    }

    public static CopyOnWriteArrayList<GalleryDownloaderV2> getDownloaders() {
        CopyOnWriteArrayList<GalleryDownloaderV2> downloaders = new CopyOnWriteArrayList<>();
        for (GalleryDownloaderManager manager : downloadQueue)
            if (manager.downloader().getStatus() != GalleryDownloaderV2.Status.CANCELED)
                downloaders.add(manager.downloader());
        return downloaders;
    }

    public static void addObserver(DownloadObserver observer) {
        for (GalleryDownloaderManager manager : downloadQueue)
            manager.downloader().addObserver(observer);
    }

    public static void removeObserver(DownloadObserver observer) {
        for (GalleryDownloaderManager manager : downloadQueue)
            manager.downloader().removeObserver(observer);
    }

    private static GalleryDownloaderManager findManagerFromDownloader(GalleryDownloaderV2 downloader) {
        for (GalleryDownloaderManager manager : downloadQueue)
            if (manager.downloader() == downloader)
                return manager;
        return null;
    }

    public static void remove(GalleryDownloaderV2 downloader, boolean cancel) {
        remove(downloader, cancel, true);
    }

    public static synchronized void remove(GalleryDownloaderV2 downloader, boolean cancel, boolean deleteFiles) {
        remove(downloader, cancel, deleteFiles, null);
    }

    public static synchronized void remove(GalleryDownloaderV2 downloader, boolean cancel, boolean deleteFiles,
                                           Runnable afterCleanup) {
        GalleryDownloaderManager manager = findManagerFromDownloader(downloader);
        if (manager == null) return;
        if (cancel) {
            downloader.cancel(deleteFiles, afterCleanup);
            return;
        }
        downloadQueue.remove(manager);
    }

    public static synchronized void givePriority(GalleryDownloaderV2 downloader) {
        GalleryDownloaderManager manager = findManagerFromDownloader(downloader);
        if (manager == null) return;
        downloadQueue.remove(manager);
        downloadQueue.add(0, manager);
        downloader.prioritize();
    }

}
