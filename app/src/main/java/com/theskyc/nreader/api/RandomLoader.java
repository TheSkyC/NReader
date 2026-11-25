package com.theskyc.nreader.api;

import com.theskyc.nreader.RandomActivity;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.utility.ImageDownloadUtility;
import com.theskyc.nreader.utility.LogUtility;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class RandomLoader {
    private static final int MAX_LOADED = 5;
    private final Queue<Gallery> galleries = new ArrayDeque<>();
    private final RandomActivity activity;
    private InspectorV3 request;
    private boolean requested;
    private boolean closed;

    public RandomLoader(RandomActivity activity) {
        this.activity = activity;
    }

    public void start(boolean needsGallery) {
        if (needsGallery) requestGallery();
        else loadRandomGallery();
    }

    public void requestGallery() {
        if (closed || requested) return;
        Gallery gallery = galleries.poll();
        if (gallery != null) {
            activity.loadGallery(gallery);
        } else {
            requested = true;
            activity.showRandomLoading();
        }
        loadRandomGallery();
    }

    private void loadRandomGallery() {
        if (closed || request != null || galleries.size() >= MAX_LOADED) return;
        request = InspectorV3.randomInspector(activity, new InspectorV3.DefaultInspectorResponse() {
            @Override
            public void onSuccess(List<GenericGallery> result) {
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    request = null;
                    if (result.isEmpty() || !(result.get(0) instanceof Gallery)
                        || !result.get(0).isValid()) {
                        handleFailure();
                        return;
                    }
                    Gallery gallery = (Gallery) result.get(0);
                    if (requested) {
                        requested = false;
                        activity.loadGallery(gallery);
                    } else {
                        galleries.add(gallery);
                        ImageDownloadUtility.preloadImage(activity, gallery.getCover());
                    }
                    loadRandomGallery();
                });
            }

            @Override
            public void onFailure(Exception error) {
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    request = null;
                    LogUtility.w("Random gallery request failed: " + error.getClass().getSimpleName());
                    handleFailure();
                });
            }
        }, false);
        request.start();
    }

    private void handleFailure() {
        if (requested) {
            requested = false;
            activity.showRandomLoadFailure();
        }
    }

    public void close() {
        closed = true;
        if (request != null) request.cancelRequest();
        request = null;
        galleries.clear();
    }
}
