// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GalleryData;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.components.TagList;
import com.maxwai.nclientv3.api.enums.Language;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.api.enums.TagType;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.classes.Size;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class SimpleGallery extends GenericGallery {
    public static final Creator<SimpleGallery> CREATOR = new Creator<>() {
        @Override
        public SimpleGallery createFromParcel(Parcel in) {
            return new SimpleGallery(in);
        }

        @Override
        public SimpleGallery[] newArray(int size) {
            return new SimpleGallery[size];
        }
    };
    private final String title;
    private final Uri thumbnail;
    private final int id, mediaId;
    private int pageCount;
    private Language language = Language.UNKNOWN;
    private TagList tags;
    private final List<Integer> tagIds = new ArrayList<>();

    public SimpleGallery(Parcel in) {
        title = in.readString();
        id = in.readInt();
        mediaId = in.readInt();
        thumbnail = Uri.parse(in.readString());
        language = Language.values()[in.readByte()];
        pageCount = Math.max(0, in.readInt());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            tags = in.readParcelable(TagList.class.getClassLoader(), TagList.class);
        } else {
            tags = in.readParcelable(TagList.class.getClassLoader());
        }
        in.readList(tagIds, Integer.class.getClassLoader());
    }

    @SuppressLint("Range")
    public SimpleGallery(Cursor c) {
        title = c.getString(c.getColumnIndex(Queries.HistoryTable.TITLE));
        id = c.getInt(c.getColumnIndex(Queries.HistoryTable.ID));
        mediaId = c.getInt(c.getColumnIndex(Queries.HistoryTable.MEDIAID));
        thumbnail = Uri.parse(c.getString(c.getColumnIndex(Queries.HistoryTable.THUMB)));
    }

    private SimpleGallery(String title, int id, int mediaId, Uri thumbnail, Language language, TagList tags) {
        this.title = title;
        this.id = id;
        this.mediaId = mediaId;
        this.thumbnail = thumbnail;
        this.language = language;
        this.tags = tags;
    }

    public SimpleGallery(Gallery gallery) {
        title = gallery.getTitle();
        mediaId = gallery.getMediaId();
        id = gallery.getId();
        thumbnail = gallery.getThumbnail();
        pageCount = gallery.getPageCount();
        language = gallery.getLanguage();
        tags = gallery.getTags();
        if (tags != null) for (Tag tag : tags.getAllTagsList()) tagIds.add(tag.getId());
    }

    /**
     * Create a SimpleGallery from API v2 GalleryListItem JSON.
     * v2 list items have: id, media_id(string), thumbnail(path string),
     * english_title, japanese_title, tag_ids(int array), num_pages
     */
    public static SimpleGallery fromV2ListItem(Context context, JSONObject json) throws JSONException {
        int id = json.getInt("id");
        int mediaId;
        try {
            mediaId = Integer.parseInt(json.getString("media_id"));
        } catch (NumberFormatException e) {
            mediaId = 0;
        }
        // Title: v2 list items use english_title/japanese_title directly
        String englishTitle = json.optString("english_title", "");
        String japaneseTitle = json.optString("japanese_title", "");
        // But v2 detail/related items may use a title object
        JSONObject titleObj = json.optJSONObject("title");
        String title;
        if (titleObj != null) {
            String pretty = titleObj.optString("pretty", "");
            String english = titleObj.optString("english", "");
            String japanese = titleObj.optString("japanese", "");
            title = !pretty.isEmpty() ? pretty : (!english.isEmpty() ? english : japanese);
        } else {
            title = !englishTitle.isEmpty() ? englishTitle : japaneseTitle;
        }
        String thumbPath = json.optString("thumbnail", "");
        // Related detail items include full tags; prefer those credits over a possibly stale tag cache.
        JSONArray tagIdsArr = json.optJSONArray("tag_ids");
        List<Integer> tagIds = new ArrayList<>();
        if (tagIdsArr != null) for (int i = 0; i < tagIdsArr.length(); i++) tagIds.add(tagIdsArr.getInt(i));
        JSONArray tagsArray = json.optJSONArray("tags");
        TagList tags;
        Language language = Language.UNKNOWN;
        if (tagsArray != null && tagsArray.length() > 0) {
            tags = new TagList();
            for (int i = 0; i < tagsArray.length(); i++) {
                JSONObject tagObj = tagsArray.getJSONObject(i);
                Tag tag = new Tag(
                    tagObj.getString("name"),
                    tagObj.optInt("count", 0),
                    tagObj.getInt("id"),
                    TagType.typeByName(tagObj.getString("type")),
                    TagStatus.DEFAULT
                );
                tags.addTag(tag);
            }
            language = Gallery.loadLanguage(tags);
        } else if (tagIdsArr != null && tagIdsArr.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tagIdsArr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(tagIdsArr.getInt(i));
            }
            tags = Queries.TagTable.getTagsFromListOfInt(sb.toString());
            language = Gallery.loadLanguage(tags);
        } else {
            tags = new TagList();
        }
        if (context != null && id > Global.getMaxId()) Global.updateMaxId(context, id);
        SimpleGallery gallery = new SimpleGallery(title, id, mediaId,
            Uri.parse("https://t1." + Utility.getHost() + "/" + thumbPath), language, tags);
        gallery.pageCount = Math.max(0, json.optInt("num_pages", 0));
        gallery.tagIds.addAll(tagIds);
        return gallery;
    }

    public void replaceTagsFromLookup(Collection<Tag> fetchedTags) {
        TagList resolved = new TagList();
        Set<Integer> fetchedIds = new HashSet<>();
        for (Tag tag : fetchedTags) {
            if (tagIds.contains(tag.getId()) && fetchedIds.add(tag.getId())) resolved.addTag(tag);
        }
        if (tags != null) for (Tag tag : tags.getAllTagsList())
            if (tagIds.contains(tag.getId()) && fetchedIds.add(tag.getId())) resolved.addTag(tag);
        tags = resolved;
        language = Gallery.loadLanguage(tags);
    }

    public List<Integer> getTagIds() { return new ArrayList<>(tagIds); }

    public TagList getTags() {
        return tags;
    }

    public boolean hasTags(Collection<Tag> tags) {
        return this.tags.hasTags(tags);
    }

    public Language getLanguage() {
        return language;
    }

    public boolean hasIgnoredTags(String s) {
        if (tags == null) return false;
        for (Tag t : tags.getAllTagsList())
            if (s.contains(t.toQueryTag(TagStatus.AVOIDED))) {
                LogUtility.d("Found: " + s + ",," + t.toQueryTag());
                return true;
            }
        return false;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public Type getType() {
        return Type.SIMPLE;
    }

    @Override
    public int getPageCount() {
        return pageCount;
    }

    @Override
    public boolean isValid() {
        return id > 0;
    }

    @Override
    @NonNull
    public String getTitle() {
        return title;
    }

    @Override
    public Size getMaxSize() {
        return null;
    }

    @Override
    public Size getMinSize() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeInt(id);
        dest.writeInt(mediaId);
        dest.writeString(thumbnail.toString());
        dest.writeByte((byte) language.ordinal());
        dest.writeInt(pageCount);
        dest.writeParcelable(tags, flags);
        dest.writeList(tagIds);
    }

    public Uri getThumbnail() {
        return thumbnail;
    }

    public int getMediaId() {
        return mediaId;
    }

    @Override
    public GalleryFolder getGalleryFolder() {
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "SimpleGallery{" +
            "language=" + language +
            ", title='" + title + '\'' +
            ", thumbnail=" + thumbnail +
            ", id=" + id +
            ", mediaId=" + mediaId +
            '}';
    }

    @Override
    public boolean hasGalleryData() {
        return false;
    }

    @Override
    public GalleryData getGalleryData() {
        return null;
    }
}
