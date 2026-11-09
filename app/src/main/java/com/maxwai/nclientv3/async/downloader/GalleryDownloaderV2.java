// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;

public class GalleryDownloaderV2 {
    public static final String DUPLICATE_EXTENSION = ".DUP";
    public static final Pattern ID_FILE = Pattern.compile("^\\.\\d{1,6}$");
    private final Context context;
    private final int id;
    private final CopyOnWriteArraySet<DownloadObserver> observers = new CopyOnWriteArraySet<>();
    private final List<PageContainer> urls = new ArrayList<>();
    private volatile Status status = Status.NOT_STARTED;
    private volatile DownloadFailure failure = DownloadFailure.NONE;
    private volatile Call activeCall;
    private volatile InspectorV3 activeInspector;
    private final Object lifecycleLock = new Object();
    private static final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
    private boolean working;
    private boolean deleteOnCancel;
    private boolean cancelCleanupComplete;
    private final List<Runnable> cancelCleanupCallbacks = new ArrayList<>();
    private boolean restartRequested;
    private String retryAfter;
    private String title;
    private Uri thumbnail;
    private int start = -1, end = -1;
    private Gallery gallery;
    private File folder;
    private boolean initialized = false;

    public GalleryDownloaderV2(Context context, @Nullable String title, @Nullable Uri thumbnail, int id) {
        this.context = context;
        this.id = id;
        this.thumbnail = thumbnail;
        this.title = Gallery.getPathTitle(title, context.getString(R.string.download_gallery));
    }

    public GalleryDownloaderV2(Context context, Gallery gallery, int start, int end) {
        this(context, gallery.getTitle(), gallery.getCover(), gallery.getId());
        this.start = start;
        this.end = end;
        setGallery(gallery);
    }

    private static File findFolder(File downloadfolder, String pathTitle, int id) {
        File folder = new File(downloadfolder, pathTitle);
        if (usableFolder(folder, id)) return folder;
        int i = 1;
        do {
            folder = new File(downloadfolder, pathTitle + DUPLICATE_EXTENSION + (i++));
        } while (!usableFolder(folder, id));
        return folder;
    }

    private static boolean usableFolder(File file, int id) {
        if (!file.exists()) return true;//folder not exists
        if (new File(file, "." + id).exists()) return true;//same id
        File[] files = file.listFiles((dir, name) -> ID_FILE.matcher(name).matches());
        if (files != null && files.length > 0) return false;//has id but not equal
        LocalGallery localGallery = new LocalGallery(file);//read id from metadata
        return localGallery.getId() == id;
    }

    public boolean hasData() {
        return gallery != null;
    }

    public void removeObserver(DownloadObserver observer) {
        observers.remove(observer);
    }

    public File getFolder() {
        return folder;
    }

    public Gallery getGallery() {
        return gallery;
    }

    private void setGallery(Gallery gallery) {
        this.gallery = gallery;
        title = gallery.getPathTitle();
        thumbnail = gallery.getThumbnail();
        if (start == -1) start = 0;
        if (end == -1) end = gallery.getPageCount() - 1;
        start = Math.max(0, Math.min(start, gallery.getPageCount() - 1));
        end = Math.max(start, Math.min(end, gallery.getPageCount() - 1));
        if (working) persist();
    }

    void persist() {
        if (gallery != null) Queries.DownloadTable.addGallery(this);
    }

    private int getTotalPage() {
        return Math.max(1, end - start + 1);
    }

    public int getPercentage() {
        if (gallery == null || !initialized) return 0;
        return ((getTotalPage() - urls.size()) * 100) / getTotalPage();
    }

    private void onStart() {
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        for (DownloadObserver observer : observers) observer.triggerStartDownload(this);
    }

    private void onEnd() {
        synchronized (lifecycleLock) {
            if (!isRunning()) return;
            status = Status.FINISHED;
        }
        Queries.DownloadTable.removeGallery(id);
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        for (DownloadObserver observer : observers) observer.triggerEndDownload(this);
    }

    private void onUpdate() {
        int total = getTotalPage();
        int reach = total - urls.size();
        for (DownloadObserver observer : observers)
            observer.triggerUpdateProgress(this, reach, total);
    }

    private void onCancel() {
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        for (DownloadObserver observer : observers) observer.triggerCancelDownload(this);
    }

    private void onPause() {
        com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        for (DownloadObserver observer : observers) observer.triggerPauseDownload(this);
    }

    public LocalGallery localGallery() {
        if ((status != Status.FINISHED && status != Status.CANCELED) || folder == null || !folder.isDirectory()) return null;
        return new LocalGallery(folder);
    }

    public String getTitle() {
        return title;
    }

    public void addObserver(DownloadObserver observer) {
        if (observer == null) return;
        observers.add(observer);
    }

    public int getId() {
        return id;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    boolean coversRequest(GalleryDownloaderV2 request) {
        if (status == Status.CANCELED || status == Status.FINISHED) return false;
        int requestedStart = Math.max(0, request.start);
        int requestedEnd = request.end >= 0 ? request.end
            : gallery == null ? Integer.MAX_VALUE : gallery.getPageCount() - 1;
        return Math.max(0, start) <= requestedStart && (end < 0 || end >= requestedEnd);
    }

    @NonNull
    public String getPathTitle() {
        return title;
    }

    @NonNull
    public String getTruePathTitle() {
        return title;
    }

    /**
     * @return true if the download has been completed, false otherwise
     */
    public boolean downloadGalleryData() {
        if (this.gallery != null) return true;
        for (int attempt = 1; attempt <= DownloadRetryPolicy.MAX_ATTEMPTS && isRunning(); attempt++) {
            InspectorV3 inspector = InspectorV3.galleryInspector(context, id, null);
            retryAfter = null;
            synchronized (lifecycleLock) {
                if (!isRunning()) return false;
                activeInspector = inspector;
            }
            try {
                if (inspector.createDocument()) {
                    inspector.parseDocument();
                    if (inspector.getGalleries() != null && !inspector.getGalleries().isEmpty()) {
                        Gallery result = (Gallery) inspector.getGalleries().get(0);
                        if (result.isValid() && result.getPageCount() > 0 && isRunning()) {
                            setGallery(result);
                            return true;
                        }
                    }
                    failure = DownloadFailure.INVALID_DATA;
                } else {
                    failure = DownloadFailure.fromHttpCode(inspector.getResponseCode());
                    retryAfter = inspector.getRetryAfter();
                }
            } catch (IOException exception) {
                failure = DownloadFailure.NETWORK;
                LogUtility.w("Download metadata request failed for gallery " + id);
            } catch (Exception exception) {
                failure = DownloadFailure.INVALID_DATA;
                LogUtility.w("Download metadata is invalid for gallery " + id);
            } finally {
                activeInspector = null;
            }
            if (!retryAfterFailure(attempt)) break;
        }
        fail();
        return false;
    }

    public Uri getThumbnail() {
        return thumbnail;
    }

    public boolean canBeFetched() {
        return status == Status.NOT_STARTED;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        if (status == Status.CANCELED) {
            cancel(true);
            return;
        }
        synchronized (lifecycleLock) {
            if (this.status == Status.CANCELED || this.status == Status.FINISHED) return;
            this.status = status;
            if (status == Status.PAUSED) {
                restartRequested = false;
                cancelRequests();
            }
        }
        if (status == Status.PAUSED) {
            onPause();
        }
    }

    public DownloadFailure getFailure() {
        return failure;
    }

    public void retry() {
        synchronized (lifecycleLock) {
            if (status == Status.CANCELED || status == Status.FINISHED || status == Status.DOWNLOADING) return;
            if (working) {
                restartRequested = true;
                return;
            }
            failure = DownloadFailure.NONE;
            status = Status.NOT_STARTED;
        }
    }

    public void cancel(boolean deleteFiles) {
        cancel(deleteFiles, null);
    }

    public void cancel(boolean deleteFiles, Runnable afterCleanup) {
        synchronized (lifecycleLock) {
            if (status == Status.FINISHED) return;
            if (afterCleanup != null) {
                if (cancelCleanupComplete) afterCleanup.run();
                else cancelCleanupCallbacks.add(afterCleanup);
            }
            if (status == Status.CANCELED) return;
            deleteOnCancel = deleteFiles;
            status = Status.CANCELED;
            restartRequested = false;
            cancelRequests();
            if (!working) cleanupExecutor.execute(this::cleanupCanceled);
        }
    }

    private void cancelRequests() {
        Call call = activeCall;
        if (call != null) call.cancel();
        InspectorV3 inspector = activeInspector;
        if (inspector != null) inspector.cancelRequest();
    }

    private void cleanupCanceled() {
        if (folder == null) folder = Global.findGalleryFolder(context, id);
        if (deleteOnCancel) {
            Global.recursiveDelete(folder);
        }
        Queries.DownloadTable.removeGallery(id);
        DownloadQueue.remove(this, false);
        onCancel();
        List<Runnable> callbacks;
        synchronized (lifecycleLock) {
            cancelCleanupComplete = true;
            callbacks = new ArrayList<>(cancelCleanupCallbacks);
            cancelCleanupCallbacks.clear();
        }
        for (Runnable callback : callbacks) callback.run();
    }

    public void deleteLocalFiles() {
        synchronized (lifecycleLock) {
            if (status != Status.CANCELED) return;
            if (!cancelCleanupComplete) {
                cancelCleanupCallbacks.add(this::deleteLocalFiles);
                return;
            }
        }
        cleanupExecutor.execute(() -> {
            if (folder == null) folder = Global.findGalleryFolder(context, id);
            Global.recursiveDelete(folder);
            com.maxwai.nclientv3.async.database.GalleryStateRepository.invalidateLocalFiles();
        });
    }

    public void runDownload() {
        synchronized (lifecycleLock) {
            if (!canBeFetched() || working) return;
            working = true;
            status = Status.DOWNLOADING;
        }
        try {
            if (downloadGalleryData() && isRunning()) download();
        } catch (RuntimeException exception) {
            failure = DownloadFailure.STORAGE;
            fail();
            LogUtility.e("Download task failed for gallery " + id, exception);
        } finally {
            boolean canceled;
            synchronized (lifecycleLock) {
                working = false;
                canceled = status == Status.CANCELED;
                if (!canceled && restartRequested) {
                    restartRequested = false;
                    failure = DownloadFailure.NONE;
                    status = Status.NOT_STARTED;
                }
            }
            if (canceled) cleanupCanceled();
            else if (status == Status.DOWNLOADING) setStatus(Status.PAUSED);
        }
    }

    private boolean isRunning() {
        return status == Status.DOWNLOADING && !Thread.currentThread().isInterrupted();
    }

    private void fail() {
        synchronized (lifecycleLock) {
            if (!isRunning()) return;
            status = Status.FAILED;
        }
        onPause();
    }

    private boolean retryAfterFailure(int attempt) {
        if (!isRunning() || !failure.retryable || attempt >= DownloadRetryPolicy.MAX_ATTEMPTS) return false;
        long delay = DownloadRetryPolicy.delayMillis(attempt, retryAfter, System.currentTimeMillis());
        if (delay < 0) return false;
        long deadline = android.os.SystemClock.elapsedRealtime() + delay;
        while (isRunning() && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(Math.min(100, Math.max(1, deadline - android.os.SystemClock.elapsedRealtime())));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                setStatus(Status.PAUSED);
                return false;
            }
        }
        return isRunning();
    }

    public void download() {
        try {
            initDownload();
        } catch (IOException exception) {
            failure = DownloadFailure.STORAGE;
            fail();
            return;
        }
        if (!isRunning()) return;
        onStart();
        while (!urls.isEmpty() && isRunning()) {
            PageContainer page = urls.get(0);
            boolean saved = false;
            for (int attempt = 1; attempt <= DownloadRetryPolicy.MAX_ATTEMPTS && isRunning(); attempt++) {
                if (savePage(page)) {
                    saved = true;
                    break;
                }
                if (!retryAfterFailure(attempt)) break;
            }
            if (!isRunning()) return;
            if (!saved) {
                fail();
                return;
            }
            urls.remove(page);
            onUpdate();
        }
        if (isRunning()) onEnd();
    }

    private boolean isCorrupted(File file) {
        String path = file.getAbsolutePath();
        String imagePath = path.endsWith(".part") ? path.substring(0, path.length() - 5) : path;
        if (imagePath.endsWith(".jpg") || imagePath.endsWith(".jpeg")) {
            return Global.isJPEGCorrupted(path);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 256;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        boolean x = bitmap == null;
        if (!x) bitmap.recycle();
        return x;
    }

    private boolean savePage(PageContainer page) {
        if (page == null) return true;
        retryAfter = null;
        File filePath = new File(folder, page.getPageName());
        if (filePath.exists() && !isCorrupted(filePath)) return true;
        File temporaryFile = new File(folder, page.getPageName() + ".part");
        Call call = Global.getClient(context).newCall(new Request.Builder().url(page.url.toString()).build());
        synchronized (lifecycleLock) {
            if (!isRunning()) return false;
            activeCall = call;
        }
        try (Response r = call.execute()) {
            if (r.code() != 200) {
                failure = DownloadFailure.fromHttpCode(r.code());
                retryAfter = r.header("Retry-After");
                return false;
            }
            if (r.body() == null) {
                failure = DownloadFailure.INVALID_DATA;
                return false;
            }
            long expectedSize = r.body().contentLength();
            long written = 0;
            OutputStream output;
            try {
                output = new FileOutputStream(temporaryFile);
            } catch (IOException exception) {
                failure = DownloadFailure.STORAGE;
                return false;
            }
            try (OutputStream destination = output; InputStream input = r.body().byteStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while (isRunning() && (count = input.read(buffer)) != -1) {
                    try {
                        destination.write(buffer, 0, count);
                    } catch (IOException exception) {
                        failure = DownloadFailure.STORAGE;
                        return false;
                    }
                    written += count;
                }
            }
            if (!isRunning()) return false;
            if (written == 0 || (expectedSize >= 0 && written != expectedSize) || isCorrupted(temporaryFile)) {
                failure = DownloadFailure.NETWORK;
                return false;
            }
            try {
                Files.move(temporaryFile.toPath(), filePath.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                failure = DownloadFailure.STORAGE;
                return false;
            }
            failure = DownloadFailure.NONE;
            return true;
        } catch (IOException exception) {
            if (failure != DownloadFailure.STORAGE) failure = DownloadFailure.NETWORK;
        } finally {
            activeCall = null;
            if (temporaryFile.exists() && !temporaryFile.delete())
                LogUtility.w("Unable to remove temporary download page for gallery " + id);
        }
        return false;
    }


    public synchronized void initDownload() throws IOException {
        if (initialized) return;
        createFolder();
        urls.clear();
        createPages();
        checkPages();
        initialized = true;
    }

    private void checkPages() {
        File filePath;
        for (int i = 0; i < urls.size(); i++) {
            if (urls.get(i) == null) {
                urls.remove(i--);
                continue;
            }
            filePath = new File(folder, urls.get(i).getPageName());
            if (filePath.exists() && !isCorrupted(filePath))
                urls.remove(i--);
        }
    }

    private void createPages() {
        for (int i = start; i <= end && i < gallery.getPageCount(); i++)
            urls.add(new PageContainer(i + 1, gallery.getHighPage(i)));
    }

    private void createFolder() throws IOException {
        if (Global.DOWNLOADFOLDER == null) throw new IOException("Download storage is unavailable");
        folder = findFolder(Global.DOWNLOADFOLDER, title, id);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Unable to create download folder");
        writeNoMedia();
        createIdFile();
    }

    private void createIdFile() throws IOException {
        File idFile = new File(folder, "." + id);
        //noinspection ResultOfMethodCallIgnored
        idFile.createNewFile();
    }

    private void writeNoMedia() throws IOException {
        File nomedia = new File(folder, ".nomedia");
        LogUtility.d("NOMEDIA: " + nomedia + " for id " + id);
        try (FileWriter writer = new FileWriter(nomedia)) {
            gallery.jsonWrite(writer);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GalleryDownloaderV2 that = (GalleryDownloaderV2) o;

        if (id != that.id) return false;
        return Objects.equals(folder, that.folder);
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + (folder != null ? folder.hashCode() : 0);
        return result;
    }

    public enum Status {NOT_STARTED, DOWNLOADING, PAUSED, FINISHED, CANCELED, FAILED}

    public static class PageContainer {
        public final int page;
        public final Uri url;

        public PageContainer(int page, Uri url) {
            this.page = page;
            this.url = url;
        }

        public String getPageName() {
            String fileName = Objects.requireNonNull(url.getLastPathSegment());
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.US);
            if (!extension.matches("jpg|jpeg|png|gif|webp")) throw new IllegalArgumentException("Unsupported page image format");
            return String.format(Locale.US, "%03d.%s", page, extension);
        }
    }
}
