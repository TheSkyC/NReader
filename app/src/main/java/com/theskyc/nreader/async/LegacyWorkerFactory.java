// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.ListenableWorker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;

import com.theskyc.nreader.async.converters.CreatePdfOrZip;
import com.theskyc.nreader.async.downloader.DownloadGalleryV2;

/** Restores WorkManager requests persisted before the namespace migration. */
public final class LegacyWorkerFactory extends WorkerFactory {
    @Nullable
    @Override
    public ListenableWorker createWorker(@NonNull Context context, @NonNull String name,
                                         @NonNull WorkerParameters parameters) {
        switch (name) {
            case "com.maxwai.nclientv3.async.ScrapeTags":
                return new ScrapeTags(context, parameters);
            case "com.maxwai.nclientv3.async.UpdateCheckWorker":
                return new UpdateCheckWorker(context, parameters);
            case "com.maxwai.nclientv3.async.UpdateDownloadWorker":
                return new UpdateDownloadWorker(context, parameters);
            case "com.maxwai.nclientv3.async.converters.CreatePdfOrZip":
                return new CreatePdfOrZip(context, parameters);
            case "com.maxwai.nclientv3.async.downloader.DownloadGalleryV2":
                return new DownloadGalleryV2(context, parameters);
            default:
                return null;
        }
    }
}
