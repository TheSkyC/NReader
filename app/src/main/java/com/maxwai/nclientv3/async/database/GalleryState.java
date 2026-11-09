// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.files.LocalDownloadState;

public final class GalleryState {
    public final boolean favorite;
    public final String statusName;
    public final int statusColor;
    public final ReadingPosition position;
    public final LocalDownloadState local;

    GalleryState(boolean favorite, String statusName, int statusColor, ReadingPosition position, LocalDownloadState local) {
        this.favorite = favorite;
        this.statusName = statusName;
        this.statusColor = statusColor;
        this.position = position;
        this.local = local;
    }
}
