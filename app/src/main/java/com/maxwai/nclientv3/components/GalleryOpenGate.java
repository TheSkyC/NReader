package com.maxwai.nclientv3.components;

/** Prevents repeated taps from enqueueing multiple gallery activities. */
public final class GalleryOpenGate {
    private static final Object LOCK = new Object();
    private static int openingGalleryId = -1;

    private GalleryOpenGate() {
    }

    public static boolean tryAcquire(int galleryId) {
        if (galleryId <= 0) return false;
        synchronized (LOCK) {
            if (openingGalleryId != -1) return false;
            openingGalleryId = galleryId;
            return true;
        }
    }

    public static void release() {
        synchronized (LOCK) {
            openingGalleryId = -1;
        }
    }
}
