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
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.NotificationSettings;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CreatePdfOrZip extends Worker {
    private static final int MAX_GALLERY_PATH_LENGTH = 2048;
    private static final String GALLERY_PATH_KEY = "GALLERY_PATH";
    private static final String PDF_OR_ZIP_KEY = "PDF_OR_ZIP";
    private int notId;
    private int totalPage;
    private NotificationCompat.Builder notification;

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
        totalPage = gallery.getPageCount();
        preExecute(gallery.getDirectory(), pdf);
        if (pdf) {
            PdfDocument document = new PdfDocument();
            try {
                File page;
                for (int a = 1; a <= gallery.getPageCount(); a++) {
                    page = gallery.getPage(a);
                    if (page == null) continue;
                    Bitmap bitmap = BitmapFactory.decodeFile(page.getAbsolutePath());
                    if (bitmap != null) {
                        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), a).create();
                        PdfDocument.Page p = document.startPage(info);
                        p.getCanvas().drawBitmap(bitmap, 0f, 0f, null);
                        document.finishPage(p);
                        bitmap.recycle();
                    }
                    notification.setProgress(totalPage - 1, a + 1, false);
                    NotificationSettings.notify(getApplicationContext(), notId, notification.build());

                }
                notification.setContentText(getApplicationContext().getString(R.string.writing_pdf));
                notification.setProgress(totalPage, 0, true);
                NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                try (ExportFile export = ExportFile.create(Global.PDFFOLDER, gallery.getTitle(), gallery.getId(), ".pdf")) {
                    File finalPath = export.getOutputFile();
                    LogUtility.d("Generating PDF at: " + finalPath.getName());
                    try (FileOutputStream out = new FileOutputStream(export.getTemporaryFile())) {
                        document.writeTo(out);
                    }
                    export.publish();
                    notification.setProgress(0, 0, false);
                    notification.setContentTitle(getApplicationContext().getString(R.string.created_pdf));
                    notification.setContentText(gallery.getTitle());
                    createIntentOpen(finalPath, true);
                    NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                } catch (IOException e) {
                    notification.setContentTitle(getApplicationContext().getString(R.string.error_pdf));
                    notification.setContentText(getApplicationContext().getString(R.string.failed));
                    notification.setProgress(0, 0, false);
                    NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                    LogUtility.e(new RuntimeException("Error generating file", e));
                    return Result.failure();
                }
            } finally {
                document.close();
            }
        } else {
            try (ExportFile export = ExportFile.create(Global.ZIPFOLDER, gallery.getTitle(), gallery.getId(), ".zip")) {
                File file = export.getOutputFile();
                try (FileOutputStream output = new FileOutputStream(export.getTemporaryFile());
                     ZipOutputStream out = new ZipOutputStream(output)) {
                    out.setLevel(Deflater.BEST_COMPRESSION);
                    File actual;
                    int read;
                    byte[] buffer = new byte[1024];
                    for (int i = 1; i <= gallery.getPageCount(); i++) {
                        actual = gallery.getPage(i);
                        if (actual == null) continue;
                        ZipEntry entry = new ZipEntry(actual.getName());
                        try (FileInputStream in = new FileInputStream(actual)) {
                            out.putNextEntry(entry);
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        out.closeEntry();
                        notification.setProgress(gallery.getPageCount(), i, false);
                        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
                    }
                    out.flush();
                }
                export.publish();
                postExecutePdf(true, gallery, null, file);
            } catch (IOException e) {
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
            .setProgress(pdf ? (totalPage - 1) : 1, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS);
        if (pdf) {
            notification.setStyle(new NotificationCompat.BigTextStyle().bigText(file.getName()));
        }
        NotificationSettings.notify(getApplicationContext(), notId, notification.build());
    }
}
