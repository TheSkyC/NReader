// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.converters;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.NotificationSettings;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CreatePdfOrZip extends Worker {
    private static final int MAX_GALLERY_PATH_LENGTH = 2048;
    private static final String GALLERY_PATH_KEY = "GALLERY_PATH";
    private static final String PDF_OR_ZIP_KEY = "PDF_OR_ZIP";
    private int notId;
    private int totalPage;
    private volatile NotificationCompat.Builder notification;
    private final PublicationGate publication = new PublicationGate();

    public CreatePdfOrZip(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void startWork(Context context, LocalGallery gallery, boolean pdf) {
        if (gallery == null || !gallery.isValid()) return;
        String galleryPath = gallery.getDirectory().getAbsolutePath();
        if (galleryPath.length() > MAX_GALLERY_PATH_LENGTH) return;
        WorkRequest createPdfOrZipWorkRequest = new OneTimeWorkRequest.Builder(CreatePdfOrZip.class)
            .setInputData(new Data.Builder()
                .putString(GALLERY_PATH_KEY, galleryPath)
                .putBoolean(PDF_OR_ZIP_KEY, pdf)
                .build())
            .build();
        WorkManager.getInstance(context).enqueue(createPdfOrZipWorkRequest);
    }

    public static boolean hasPDFCapabilities() {
        try {
            Class.forName("android.graphics.pdf.PdfDocument");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            return export();
        } finally {
            if (isStopped() && notification != null) NotificationSettings.cancel(notId);
        }
    }

    private Result export() {
        notId = NotificationSettings.getNotificationId();
        System.gc();
        if (!getInputData().hasKeyWithValueOfType(PDF_OR_ZIP_KEY, Boolean.class)) {
            return Result.failure();
        }
        boolean pdf = getInputData().getBoolean(PDF_OR_ZIP_KEY, false);
        if (pdf && !hasPDFCapabilities()) {
            return Result.failure();
        }
        String galleryPath = getInputData().getString(GALLERY_PATH_KEY);
        if (galleryPath == null || galleryPath.isEmpty() || galleryPath.length() > MAX_GALLERY_PATH_LENGTH) {
            return Result.failure();
        }
        LocalGallery gallery = loadGallery(galleryPath);
        if (gallery == null) {
            return Result.failure();
        }
        List<PageFile> pages = exportPages(gallery);
        totalPage = pages.size();
        preExecute(gallery.getDirectory(), pdf);
        if (pdf) {
            PdfDocument document = new PdfDocument();
            try {
                for (int a = 1; a <= pages.size(); a++) {
                    checkStopped(this::isStopped);
                    File page = pages.get(a - 1);
                    appendPdfPage(document, page, a);
                    notification.setProgress(totalPage, a, false);
                    NotificationSettings.notify(getApplicationContext(), notId, notification.build());

                }
                checkStopped(this::isStopped);
                notification.setContentText(getApplicationContext().getString(R.string.writing_pdf));
                notification.setProgress(totalPage, 0, true);
                NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                try (ExportFile export = ExportFile.create(Global.PDFFOLDER, gallery.getTitle(), gallery.getId(), ".pdf")) {
                    File finalPath = export.getOutputFile();
                    LogUtility.d("Generating PDF at: " + finalPath.getName());
                    try (FileOutputStream out = new FileOutputStream(export.getTemporaryFile())) {
                        document.writeTo(out);
                    }
                    publication.commit(this::isStopped, () -> {
                        export.publish();
                        notification.setProgress(0, 0, false);
                        notification.setContentTitle(getApplicationContext().getString(R.string.created_pdf));
                        notification.setContentText(gallery.getTitle());
                        createIntentOpen(finalPath, true);
                        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                    });
                }
            } catch (IOException e) {
                if (isStopped()) {
                    NotificationSettings.cancel(notId);
                    return Result.failure();
                }
                notification.setContentTitle(getApplicationContext().getString(R.string.error_pdf));
                notification.setContentText(getApplicationContext().getString(R.string.failed));
                notification.setProgress(0, 0, false);
                NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                LogUtility.e(new RuntimeException("Error generating file", e));
                return Result.failure();
            } finally {
                document.close();
            }
        } else {
            try (ExportFile export = ExportFile.create(Global.ZIPFOLDER, gallery.getTitle(), gallery.getId(), ".zip")) {
                File file = export.getOutputFile();
                try (FileOutputStream output = new FileOutputStream(export.getTemporaryFile());
                     ZipOutputStream out = new ZipOutputStream(output)) {
                    out.setLevel(Deflater.BEST_COMPRESSION);
                    int completed = 0;
                    for (File page : pages) {
                        writeZipPage(out, page, this::isStopped);
                        notification.setProgress(totalPage, ++completed, false);
                        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                    }
                    out.flush();
                }
                publication.commit(this::isStopped, () -> {
                    export.publish();
                    postExecutePdf(true, gallery, null, file);
                });
            } catch (IOException e) {
                if (isStopped()) {
                    NotificationSettings.cancel(notId);
                    return Result.failure();
                }
                LogUtility.e(e.getLocalizedMessage(), e);
                postExecutePdf(false, gallery, e.getLocalizedMessage(), null);
                return Result.failure();
            }
        }
        return Result.success();
    }

    static LocalGallery loadGallery(String path) {
        try {
            File directory = new File(path).getCanonicalFile();
            if (!directory.isDirectory()) return null;
            LocalGallery gallery = new LocalGallery(directory);
            return gallery.isValid() && gallery.getPageCount() > 0 ? gallery : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    static List<PageFile> exportPages(LocalGallery gallery) {
        List<PageFile> pages = new ArrayList<>();
        for (PageFile page : gallery.getGalleryFolder()) pages.add(page);
        return pages;
    }

    static void appendPdfPage(PdfDocument document, File page, int pageNumber) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(page.getAbsolutePath());
        if (bitmap == null) throw new IOException("Unable to decode export page");
        try {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), pageNumber).create();
            PdfDocument.Page pdfPage = document.startPage(info);
            pdfPage.getCanvas().drawBitmap(bitmap, 0f, 0f, null);
            document.finishPage(pdfPage);
        } finally {
            bitmap.recycle();
        }
    }

    static void writeZipPage(ZipOutputStream out, File page) throws IOException {
        writeZipPage(out, page, () -> false);
    }

    static void writeZipPage(ZipOutputStream out, File page, BooleanSupplier stopped) throws IOException {
        checkStopped(stopped);
        try (FileInputStream in = new FileInputStream(page)) {
            out.putNextEntry(new ZipEntry(page.getName()));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkStopped(stopped);
                out.write(buffer, 0, read);
            }
            checkStopped(stopped);
            out.closeEntry();
        }
    }

    private static void checkStopped(BooleanSupplier stopped) throws InterruptedIOException {
        if (stopped.getAsBoolean()) throw new InterruptedIOException("Export stopped");
    }

    @Override
    public void onStopped() {
        publication.stop(() -> {
            if (notification != null) NotificationSettings.cancel(notId);
        });
    }

    static final class PublicationGate {
        private static final int RUNNING = 0;
        private static final int COMMITTING = 1;
        private static final int STOPPED = 2;
        private final AtomicInteger state = new AtomicInteger(RUNNING);

        interface CommitAction {
            void run() throws IOException;
        }

        void commit(BooleanSupplier workerStopped, CommitAction action) throws IOException {
            if (workerStopped.getAsBoolean() || !state.compareAndSet(RUNNING, COMMITTING))
                throw new InterruptedIOException("Export stopped");
            if (workerStopped.getAsBoolean() || state.get() == STOPPED)
                throw new InterruptedIOException("Export stopped");
            action.run();
        }

        void stop(Runnable cleanup) {
            state.set(STOPPED);
            cleanup.run();
        }
    }

    private void postExecutePdf(boolean success, LocalGallery gallery, String localizedMessage, File file) {
        notification.setProgress(0, 0, false)
            .setContentTitle(success ? getApplicationContext().getString(R.string.created_zip) : getApplicationContext().getString(R.string.failed_zip));
        if (!success) {
            notification.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(gallery.getTitle())
                .setSummaryText(localizedMessage));
        } else {
            createIntentOpen(file, false);
        }
        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
    }

    private void createIntentOpen(File finalPath, boolean pdf) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri apkURI = FileProvider.getUriForFile(
                getApplicationContext(), getApplicationContext().getPackageName() + ".provider", finalPath);
            i.setDataAndType(apkURI, pdf ? "application/pdf" : "application/zip");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

            notification.setContentIntent(PendingIntent.getActivity(getApplicationContext(), notId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } catch (IllegalArgumentException exception) {
            LogUtility.w("Unable to prepare export notification action", exception);
        }
    }

    private void preExecute(File file, boolean pdf) {
        notification = new NotificationCompat.Builder(getApplicationContext(), pdf ? Global.CHANNEL_ID2 : Global.CHANNEL_ID3);
        notification.setSmallIcon(pdf ? R.drawable.ic_pdf : R.drawable.ic_archive)
            .setOnlyAlertOnce(true)
            .setContentText(pdf ? getApplicationContext().getString(R.string.parsing_pages) : file.getName())
            .setContentTitle(getApplicationContext().getString(pdf ? R.string.channel2_title : R.string.channel3_title))
            .setProgress(totalPage, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS);
        if (pdf) {
            notification.setStyle(new NotificationCompat.BigTextStyle().bigText(file.getName()));
        }
        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
    }
}
