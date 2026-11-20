// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.classes;

import androidx.annotation.NonNull;

/** A named page marker owned by a gallery reader session. */
public final class ReaderBookmark {
    public final long id;
    public final int galleryId;
    public final int page;
    @NonNull public final String title;
    @NonNull public final String note;
    public final long createdAt;
    public final long updatedAt;

    public ReaderBookmark(long id, int galleryId, int page, @NonNull String title,
                          @NonNull String note, long createdAt, long updatedAt) {
        this.id = id;
        this.galleryId = galleryId;
        this.page = page;
        this.title = title;
        this.note = note;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
