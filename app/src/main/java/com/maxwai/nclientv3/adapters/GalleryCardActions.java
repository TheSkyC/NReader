// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.downloader.DownloadGalleryV2;
import com.maxwai.nclientv3.async.downloader.DownloadQueue;
import com.maxwai.nclientv3.async.downloader.GalleryDownloaderV2;
import com.maxwai.nclientv3.files.DownloadIntegrity;
import com.maxwai.nclientv3.components.views.RangeSelector;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.List;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class GalleryCardActions {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private GalleryCardActions() {}

    private static boolean active(View anchor) {
        Context context = anchor.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                return anchor.isAttachedToWindow() && !activity.isFinishing() && !activity.isDestroyed();
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return anchor.isAttachedToWindow();
    }

    private static View feedbackAnchor(View anchor) {
        Context context = anchor.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return ((Activity) context).findViewById(android.R.id.content);
            context = ((ContextWrapper) context).getBaseContext();
        }
        return anchor;
    }

    static void favorite(View anchor, GenericGallery gallery, boolean remove,
                         BooleanSupplier current, Runnable finished) {
        View feedback = feedbackAnchor(anchor);
        if (remove) {
            executor.execute(() -> {
                try {
                    GenericGallery saved = Queries.GalleryTable.galleryFromId(anchor.getContext(), gallery.getId());
                    Queries.FavoriteTable.removeFavorite(gallery.getId());
                    main.post(() -> {
                        if (!active(feedback)) return;
                        if (current.getAsBoolean()) finished.run();
                        Snackbar.make(feedback, R.string.cover_favorite_removed, Snackbar.LENGTH_LONG)
                            .setAction(R.string.cover_undo, view -> favorite(feedback,
                                saved == null ? gallery : saved, false, current, finished)).show();
                    });
                } catch (RuntimeException exception) {
                    fail(anchor, current, finished, exception);
                }
            });
        } else if (gallery instanceof Gallery && gallery.isValid() && gallery.getPageCount() > 0) {
            saveFavorite(anchor, (Gallery) gallery, current, finished);
        } else if (gallery instanceof com.maxwai.nclientv3.api.SimpleGallery && gallery.isValid()) {
            saveFavorite(anchor, gallery, current, finished);
        } else {
            resolve(anchor, gallery, current, resolved -> saveFavorite(anchor, resolved, current, finished), finished);
        }
    }

    private static void saveFavorite(View anchor, GenericGallery gallery, BooleanSupplier current, Runnable finished) {
        View feedback = feedbackAnchor(anchor);
        executor.execute(() -> {
            try {
                Queries.FavoriteTable.addFavorite(gallery);
                main.post(() -> {
                    if (!active(feedback)) return;
                    if (current.getAsBoolean()) finished.run();
                    Snackbar.make(feedback, R.string.cover_favorite_added, Snackbar.LENGTH_SHORT).show();
                });
            } catch (RuntimeException exception) {
                fail(anchor, current, finished, exception);
            }
        });
    }

    private static void resolve(View anchor, GenericGallery gallery, BooleanSupplier current,
                                Consumer<Gallery> result, Runnable finished) {
        executor.execute(() -> {
            try {
                Gallery saved = gallery instanceof Gallery ? (Gallery) gallery
                    : Queries.GalleryTable.galleryFromId(anchor.getContext(), gallery.getId());
                if (saved != null && saved.isValid() && saved.getPageCount() > 0 && !saved.getGalleryData().isIncomplete()) {
                    main.post(() -> {
                        result.accept(saved);
                    });
                    return;
                }
                InspectorV3.galleryInspector(anchor.getContext(), gallery.getId(), new InspectorV3.DefaultInspectorResponse() {
                    @Override
                    public void onSuccess(List<GenericGallery> galleries) {
                        if (galleries.size() != 1 || !(galleries.get(0) instanceof Gallery)
                            || galleries.get(0).getId() != gallery.getId()
                            || !galleries.get(0).isValid() || galleries.get(0).getPageCount() <= 0) {
                            fail(anchor, current, finished, null);
                            return;
                        }
                        main.post(() -> {
                            result.accept((Gallery) galleries.get(0));
                        });
                    }

                    @Override
                    public void onFailure(Exception exception) {
                        fail(anchor, current, finished, exception);
                    }
                }).start();
            } catch (RuntimeException exception) {
                fail(anchor, current, finished, exception);
            }
        });
    }

    private static void fail(View anchor, BooleanSupplier current, Runnable finished, Exception exception) {
        if (exception != null) LogUtility.w("Unable to complete gallery card action", exception);
        main.post(() -> {
            if (!active(anchor) || !current.getAsBoolean()) return;
            finished.run();
            Snackbar.make(anchor, R.string.unable_to_connect_to_the_site, Snackbar.LENGTH_LONG).show();
        });
    }

    static void download(View anchor, GenericGallery gallery, BooleanSupplier current, Runnable finished) {
        if (!Global.hasStoragePermission(anchor.getContext())) {
            Snackbar.make(anchor, R.string.download_error_storage, Snackbar.LENGTH_LONG).show();
            finished.run();
            return;
        }
        resolve(anchor, gallery, current, resolved -> {
            if (!active(anchor) || !current.getAsBoolean()) return;
            finished.run();
            if (resolved.getPageCount() > 0) new RangeSelector(anchor.getContext(), resolved).show();
        }, finished);
    }

    static void files(View anchor, GenericGallery gallery, int label, int detail,
                      boolean repair, BooleanSupplier current, Runnable finished) {
        BottomSheetDialog dialog = sheet(anchor.getContext(), gallery.getTitle(), anchor.getContext().getString(label));
        if (detail != 0) ((TextView) dialog.findViewById(R.id.sheet_detail)).setText(detail);
        if (repair && gallery.getId() > 0) {
            action(dialog, R.string.download_repair, false, () -> {
                resolve(anchor, gallery, current, resolved -> {
                    File folder = Global.findGalleryFolder(anchor.getContext(), resolved.getId());
                    boolean insideDownloadRoot = false;
                    try {
                        insideDownloadRoot = folder != null && Global.DOWNLOADFOLDER != null
                            && folder.getCanonicalPath().startsWith(Global.DOWNLOADFOLDER.getCanonicalPath() + File.separator);
                    } catch (java.io.IOException ignored) {
                        insideDownloadRoot = false;
                    }
                    if (!insideDownloadRoot) {
                        ((TextView) dialog.findViewById(R.id.sheet_detail)).setText(R.string.download_repair_unavailable);
                        return;
                    }
                    executor.execute(() -> {
                        DownloadIntegrity.Result result = DownloadIntegrity.inspect(folder);
                        main.post(() -> {
                            if (!dialog.isShowing() || !active(anchor) || !current.getAsBoolean()) return;
                            if (result.state == com.maxwai.nclientv3.files.LocalDownloadState.UNVERIFIED) {
                                ((TextView) dialog.findViewById(R.id.sheet_detail)).setText(R.string.download_repair_unavailable);
                                return;
                            }
                            String message = anchor.getContext().getString(R.string.download_repair_pages,
                                result.missing.size(), pageList(result.missing), result.damaged.size(), pageList(result.damaged));
                            ((TextView) dialog.findViewById(R.id.sheet_detail)).setText(message);
                            if (result.needsRepair()) action(dialog, R.string.download_repair_start,
                                () -> DownloadGalleryV2.repair(anchor.getContext(), resolved, folder,
                                    mergePages(result.missing, result.damaged)));
                        });
                    });
                }, finished);
            });
        }
        action(dialog, android.R.string.ok, () -> {});
        dialog.show();
    }

    private static List<Integer> mergePages(List<Integer> missing, List<Integer> damaged) {
        java.util.ArrayList<Integer> pages = new java.util.ArrayList<>(missing);
        for (Integer page : damaged) if (!pages.contains(page)) pages.add(page);
        java.util.Collections.sort(pages);
        return pages;
    }

    private static String pageList(List<Integer> pages) {
        if (pages.isEmpty()) return "-";
        return android.text.TextUtils.join(", ", pages);
    }

    static void task(View anchor, GalleryDownloaderV2 downloader) {
        Context context = anchor.getContext();
        String detail = context.getString(taskLabel(downloader));
        if (downloader.getStatus() == GalleryDownloaderV2.Status.DOWNLOADING)
            detail = context.getString(R.string.cover_download_running, downloader.getPercentage());
        if (downloader.getStatus() == GalleryDownloaderV2.Status.FAILED)
            detail = context.getString(failureMessage(downloader));
        BottomSheetDialog dialog = sheet(context, downloader.getTitle(), detail);
        boolean running = downloader.getStatus() == GalleryDownloaderV2.Status.DOWNLOADING;
        action(dialog, running ? R.string.pause_download : R.string.start_download, () -> {
            if (running) downloader.setStatus(GalleryDownloaderV2.Status.PAUSED);
            else {
                downloader.retry();
                DownloadQueue.givePriority(downloader);
                DownloadGalleryV2.startWork(context);
            }
        });
        action(dialog, R.string.stop_download, () -> {
            DownloadQueue.remove(downloader, true, false);
            View feedback = feedbackAnchor(anchor);
            final boolean[] undone = {false};
            Snackbar snackbar = Snackbar.make(feedback, R.string.download_stopped, Snackbar.LENGTH_LONG)
                .setAction(R.string.cover_undo, view -> {
                    undone[0] = true;
                    DownloadGalleryV2.restartAfterCancel(context, downloader);
                });
            snackbar.addCallback(new Snackbar.Callback() {
                @Override
                public void onDismissed(Snackbar transientBottomBar, int event) {
                    if (!undone[0] && !downloader.isRepairTask()) downloader.deleteLocalFiles();
                }
            });
            snackbar.show();
        });
        dialog.show();
    }

    static void task(Context context, GalleryDownloaderV2 downloader) {
        View anchor = context instanceof Activity
            ? ((Activity) context).findViewById(android.R.id.content)
            : new View(context);
        task(anchor, downloader);
    }

    static int taskLabel(GalleryDownloaderV2 downloader) {
        switch (downloader.getStatus()) {
            case DOWNLOADING: return R.string.start_download;
            case PAUSED: return R.string.cover_download_paused;
            case FAILED: return R.string.download_failed;
            default: return R.string.cover_download_queued;
        }
    }

    private static int failureMessage(GalleryDownloaderV2 downloader) {
        switch (downloader.getFailure()) {
            case AUTHENTICATION: return R.string.download_error_authentication;
            case RATE_LIMIT: return R.string.download_error_rate_limit;
            case STORAGE: return R.string.download_error_storage;
            case NOT_FOUND: return R.string.download_error_not_found;
            case INVALID_DATA: return R.string.download_error_invalid_data;
            default: return R.string.download_error_network;
        }
    }

    private static BottomSheetDialog sheet(Context context, String title, String detail) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(R.layout.gallery_download_sheet);
        ((TextView) dialog.findViewById(R.id.sheet_title)).setText(title);
        ((TextView) dialog.findViewById(R.id.sheet_detail)).setText(detail);
        return dialog;
    }

    private static void action(BottomSheetDialog dialog, int label, Runnable callback) {
        action(dialog, label, true, callback);
    }

    private static void action(BottomSheetDialog dialog, int label, boolean dismiss, Runnable callback) {
        LinearLayout actions = dialog.findViewById(R.id.sheet_actions);
        MaterialButton button = new MaterialButton(dialog.getContext());
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setTextColor(MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary));
        button.setText(label);
        button.setOnClickListener(view -> {
            if (dismiss) dialog.dismiss();
            callback.run();
        });
        actions.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
