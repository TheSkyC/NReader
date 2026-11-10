// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.files;

import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class DownloadIntegrity {
    private DownloadIntegrity() {}

    public static Result inspect(File directory) {
        List<Integer> missing = new ArrayList<>();
        List<Integer> damaged = new ArrayList<>();
        if (directory == null || !directory.isDirectory() || !directory.canRead())
            return new Result(LocalDownloadState.UNVERIFIED, missing, damaged, 0);
        int expected = LocalDownloadState.expectedPages(directory);
        if (expected <= 0) return new Result(LocalDownloadState.UNVERIFIED, missing, damaged, 0);
        GalleryFolder folder;
        try {
            folder = new GalleryFolder(directory);
        } catch (RuntimeException exception) {
            return new Result(LocalDownloadState.UNVERIFIED, missing, damaged, expected);
        }
        for (int page = 1; page <= expected; page++) {
            File file = folder.getPage(page);
            if (file == null) { missing.add(page); continue; }
            if (!file.isFile() || !file.canRead() || file.length() == 0) { damaged.add(page); continue; }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) damaged.add(page);
        }
        LocalDownloadState state = missing.isEmpty() && damaged.isEmpty()
            ? LocalDownloadState.COMPLETE : damaged.isEmpty() ? LocalDownloadState.PARTIAL : LocalDownloadState.DAMAGED;
        return new Result(state, missing, damaged, expected);
    }

    public static final class Result {
        public final LocalDownloadState state;
        public final List<Integer> missing;
        public final List<Integer> damaged;
        public final int expectedPages;

        Result(LocalDownloadState state, List<Integer> missing, List<Integer> damaged, int expectedPages) {
            this.state = state;
            this.missing = missing;
            this.damaged = damaged;
            this.expectedPages = expectedPages;
        }

        public boolean needsRepair() { return !missing.isEmpty() || !damaged.isEmpty(); }
    }
}
