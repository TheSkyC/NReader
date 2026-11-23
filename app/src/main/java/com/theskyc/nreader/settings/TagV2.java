package com.theskyc.nreader.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;

import java.util.List;
import java.util.Set;

@SuppressWarnings({"unused", "UnusedReturnValue"})
public class TagV2 {
    public static final int MAXTAGS = 100;
    public static final int DEFAULT_MIN_COUNT = 25;
    private static int minCount;
    private static boolean sortByName;
    @Nullable
    private static TagStatus statusFilter;
    private static boolean translatedOnly;

    public static List<Tag> getTagSet(TagType type) {
        return Queries.TagTable.getAllTagOfType(type);
    }

    public static List<Tag> getTagStatus(TagStatus status) {
        return Queries.TagTable.getAllStatus(status);
    }

    public static String getQueryString(String query, @NonNull Set<Tag> all) {
        StringBuilder builder = new StringBuilder();
        for (Tag t : all)
            if (TagCatalog.isCanonicalName(t.getName()) && !query.contains(t.getName()))
                builder.append('+').append(t.toQueryTag());
        return builder.toString();
    }

    public static List<Tag> getListPrefer(boolean removeIgnoredGalleries) {
        return removeIgnoredGalleries ? Queries.TagTable.getAllFiltered() :
            Queries.TagTable.getAllStatus(TagStatus.ACCEPTED);
    }

    public static TagStatus updateStatus(Tag t) {
        TagStatus old = t.getStatus();
        switch (t.getStatus()) {
            case ACCEPTED:
                t.setStatus(TagStatus.AVOIDED);
                break;
            case AVOIDED:
                t.setStatus(TagStatus.DEFAULT);
                break;
            case DEFAULT:
                t.setStatus(TagStatus.ACCEPTED);
                break;
        }
        if (Queries.TagTable.updateTag(t) == 1) return t.getStatus();
        t.setStatus(old);
        return old;
    }

    public static void resetAllStatus() {
        Queries.TagTable.resetAllStatus();
    }

    public static boolean containTag(Tag[] tags, Tag t) {
        for (Tag t1 : tags) if (t.equals(t1)) return true;
        return false;
    }


    public static TagStatus getStatus(Tag tag) {
        return Queries.TagTable.getStatus(tag);
    }


    public static boolean maxTagReached() {
        return getListPrefer(Global.removeAvoidedGalleries()).size() >= MAXTAGS;
    }

    public static void updateMinCount(Context context, int min) {
        context.getSharedPreferences("ScrapedTags", 0).edit().putInt("min_count", minCount = min).apply();
    }

    public static void initMinCount(Context context) {
        minCount = context.getSharedPreferences("ScrapedTags", 0).getInt("min_count", DEFAULT_MIN_COUNT);
    }

    public static void initSortByName(Context context) {
        sortByName = context.getSharedPreferences("ScrapedTags", 0).getBoolean("sort_by_name", false);
    }

    public static void initStatusFilter(Context context) {
        int ordinal = context.getSharedPreferences("ScrapedTags", 0).getInt("status_filter", -1);
        statusFilter = ordinal >= 0 && ordinal < TagStatus.values().length ? TagStatus.values()[ordinal] : null;
    }

    @Nullable
    public static TagStatus getStatusFilter() {
        return statusFilter;
    }

    public static void updateStatusFilter(Context context, @Nullable TagStatus status) {
        statusFilter = status;
        context.getSharedPreferences("ScrapedTags", 0).edit()
            .putInt("status_filter", status == null ? -1 : status.ordinal()).apply();
    }

    public static void initTranslatedOnly(Context context) {
        translatedOnly = context.getSharedPreferences("ScrapedTags", 0)
            .getBoolean("translated_only", false);
    }

    public static boolean isTranslatedOnly() {
        return translatedOnly;
    }

    public static boolean updateTranslatedOnly(Context context) {
        translatedOnly = !translatedOnly;
        context.getSharedPreferences("ScrapedTags", 0).edit()
            .putBoolean("translated_only", translatedOnly).apply();
        return translatedOnly;
    }

    public static void resetFilterPreferences(Context context) {
        minCount = DEFAULT_MIN_COUNT;
        sortByName = false;
        statusFilter = null;
        translatedOnly = false;
        context.getSharedPreferences("ScrapedTags", 0).edit()
            .remove("min_count")
            .remove("sort_by_name")
            .remove("status_filter")
            .remove("translated_only")
            .apply();
    }

    public static boolean updateSortByName(Context context) {
        context.getSharedPreferences("ScrapedTags", 0).edit().putBoolean("sort_by_name", sortByName = !sortByName).apply();
        return sortByName;
    }

    public static boolean isSortedByName() {
        return sortByName;
    }

    public static int getMinCount() {
        return minCount;
    }

    public static String getAvoidedTags() {
        StringBuilder builder = new StringBuilder();
        List<Tag> tags = Queries.TagTable.getAllStatus(TagStatus.AVOIDED);
        for (Tag t : tags) builder.append('+').append(t.toQueryTag(TagStatus.AVOIDED));
        return builder.toString();
    }
}
