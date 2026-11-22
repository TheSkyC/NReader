package com.theskyc.nreader.api;

import android.os.Binder;

import androidx.annotation.NonNull;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.settings.Login;

import java.util.ArrayList;
import java.util.List;

/** A local Binder keeps account selections out of the serialized intent. */
public final class AccountSearchTags extends Binder {
    final Login.OnlineTagScope scope;
    final List<Tag> tags;

    public AccountSearchTags(@NonNull Login.OnlineTagScope scope, @NonNull List<Tag> tags) {
        this.scope = scope;
        this.tags = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            this.tags.add(new Tag(tag.getName(), tag.getCount(), tag.getId(), tag.getType(), TagStatus.AVOIDED));
        }
    }
}
