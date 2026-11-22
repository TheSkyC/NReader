package com.theskyc.nreader.components.classes;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;

public class Bookmark {
    public final String url;
    public final int page, tag;
    private final ApiRequestType requestType;
    private final Tag tagVal;
    private final Uri uri;

    public Bookmark(String url, int page, ApiRequestType requestType, int tag) {
        this.url = url;
        this.page = page;
        this.requestType = requestType;
        this.tag = tag;
        Tag metadata = requestType == ApiRequestType.BYTAG && tag > 0 ? Queries.TagTable.getTagById(tag) : null;
        this.tagVal = metadata != null && metadata.getType().getId() > 0
            && TagCatalog.isCanonicalName(metadata.getName()) ? metadata : null;
        this.uri = Uri.parse(url);
    }

    @Nullable
    private String getSearchQuery() {
        String query = uri.getQueryParameter("query");
        return query == null ? uri.getQueryParameter("q") : query;
    }

    @Nullable
    public InspectorV3 createInspector(Context context, InspectorV3.InspectorResponse response) {
        String query = getSearchQuery();
        SortType popular = SortType.findFromAddition(uri.getQueryParameter("sort"));
        if (requestType == ApiRequestType.FAVORITE)
            return InspectorV3.favoriteInspector(context, query, page, response);
        if (requestType == ApiRequestType.BYSEARCH)
            return InspectorV3.savedSearchInspector(context, query, page, popular, response);
        if (requestType == ApiRequestType.BYALL)
            return InspectorV3.savedHomeInspector(context, page, response);
        if (requestType == ApiRequestType.BYTAG) {
            if (tagVal == null) {
                return query == null || query.trim().isEmpty() ? null
                    : InspectorV3.savedSearchInspector(context, query, page, popular, response);
            }
            return InspectorV3.savedTagInspector(context, tagVal, page, popular, response);
        }
        return null;
    }

    public void deleteBookmark() {
        Queries.BookmarkTable.deleteBookmark(url);
    }

    @NonNull
    @Override
    public String toString() {
        if (requestType == ApiRequestType.BYTAG && tagVal != null)
            return tagVal.getType().getSingle() + ": " + tagVal.getName();
        if (requestType == ApiRequestType.FAVORITE) return "Favorite";
        if (requestType == ApiRequestType.BYSEARCH || requestType == ApiRequestType.BYTAG)
            return getSearchQuery() == null ? "" : getSearchQuery();
        if (requestType == ApiRequestType.BYALL) return "Main page";
        return "WTF";
    }
}
