// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import com.theskyc.nreader.components.classes.ReadingPosition;
import com.theskyc.nreader.files.LocalDownloadState;

public final class GalleryState {
    public final boolean favorite;
    public final String statusName;
    public final int statusColor;
    public final ReadingPosition position;
    public final LocalDownloadState local;
    public final int expectedPages;
    public final boolean hasLocalFiles;

    GalleryState(boolean favorite, String statusName, int statusColor, ReadingPosition position,
                 LocalDownloadState local, int expectedPages, boolean hasLocalFiles) {
        this.favorite = favorite;
        this.statusName = statusName;
        this.statusColor = statusColor;
        this.position = position;
        this.local = local;
        this.expectedPages = expectedPages;
        this.hasLocalFiles = hasLocalFiles;
    }
}
