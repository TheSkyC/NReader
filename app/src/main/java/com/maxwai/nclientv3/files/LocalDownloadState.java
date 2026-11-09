// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.files;

import android.graphics.BitmapFactory;
import android.util.JsonReader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public enum LocalDownloadState {
    ABSENT, COMPLETE, PARTIAL, DAMAGED, UNVERIFIED;

    public static LocalDownloadState inspect(File directory) {
        if (directory == null) return ABSENT;
        if (!directory.isDirectory() || !directory.canRead()) return UNVERIFIED;
        int expectedPages = expectedPages(directory);
        if (expectedPages <= 0 || expectedPages > 10000) return UNVERIFIED;
        GalleryFolder folder = new GalleryFolder(directory);
        boolean missing = false;
        for (int page = 1; page <= expectedPages; page++) {
            File image = folder.getPage(page);
            if (image == null) {
                missing = true;
                continue;
            }
            if (!image.isFile() || !image.canRead()) return UNVERIFIED;
            if (image.length() == 0) return DAMAGED;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return DAMAGED;
        }
        return missing ? PARTIAL : COMPLETE;
    }

    public static int expectedPages(File directory) {
        if (directory == null) return 0;
        try (JsonReader reader = new JsonReader(new FileReader(new File(directory, ".nomedia")))) {
            int count = -1;
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("num_pages")) count = reader.nextInt();
                else reader.skipValue();
            }
            reader.endObject();
            return count > 0 && count <= 10000 ? count : 0;
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }
}
