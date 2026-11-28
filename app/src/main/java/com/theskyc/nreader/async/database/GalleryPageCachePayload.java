// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GalleryPageCachePayload {
    static final int SCHEMA_VERSION = 1;

    private static final int MAX_ITEMS_PER_PAGE = 100;
    private static final int MAX_TAGS_PER_GALLERY = 256;
    private static final int MAX_FIELD_LENGTH = 8192;
    private static final int MAX_TAG_FIELD_LENGTH = 256;

    private GalleryPageCachePayload() {
    }

    @Nullable
    static String create(String responseJson, int pageCount) {
        try {
            JSONObject response = new JSONObject(responseJson);
            JSONArray result = response.optJSONArray("result");
            if (result == null || result.length() > MAX_ITEMS_PER_PAGE) return null;
            JSONArray safeResults = new JSONArray();
            for (int index = 0; index < result.length(); index++) {
                JSONObject item = sanitizeGallery(result.optJSONObject(index));
                if (item == null) return null;
                safeResults.put(item);
            }
            JSONObject page = new JSONObject();
            page.put("cache_version", SCHEMA_VERSION);
            page.put("num_pages", Math.max(0, pageCount));
            page.put("result", safeResults);
            return page.toString();
        } catch (JSONException | RuntimeException error) {
            return null;
        }
    }

    static boolean isValid(String payload) {
        try {
            JSONObject page = new JSONObject(payload);
            JSONArray results = page.optJSONArray("result");
            if (page.optInt("cache_version", -1) != SCHEMA_VERSION || results == null
                || results.length() > MAX_ITEMS_PER_PAGE || page.optInt("num_pages", -1) < 0) return false;
            for (int index = 0; index < results.length(); index++) {
                JSONObject item = results.optJSONObject(index);
                if (item == null || item.optInt("id", -1) <= 0) return false;
            }
            return true;
        } catch (JSONException | RuntimeException error) {
            return false;
        }
    }

    @Nullable
    private static JSONObject sanitizeGallery(@Nullable JSONObject source) throws JSONException {
        if (source == null) return null;
        int id = source.optInt("id", -1);
        if (id <= 0) return null;
        JSONObject item = new JSONObject();
        item.put("id", id);
        Object mediaId = source.opt("media_id");
        if (mediaId instanceof String || mediaId instanceof Number) {
            String mediaIdText = String.valueOf(mediaId);
            if (mediaIdText.length() <= MAX_TAG_FIELD_LENGTH) item.put("media_id", mediaIdText);
        }
        if (!item.has("media_id")) item.put("media_id", "0");
        copyString(source, item, "thumbnail", MAX_FIELD_LENGTH);
        copyString(source, item, "english_title", MAX_FIELD_LENGTH);
        copyString(source, item, "japanese_title", MAX_FIELD_LENGTH);
        JSONObject title = source.optJSONObject("title");
        if (title != null) {
            JSONObject safeTitle = new JSONObject();
            copyString(title, safeTitle, "pretty", MAX_FIELD_LENGTH);
            copyString(title, safeTitle, "english", MAX_FIELD_LENGTH);
            copyString(title, safeTitle, "japanese", MAX_FIELD_LENGTH);
            item.put("title", safeTitle);
        }
        item.put("num_pages", Math.max(0, source.optInt("num_pages", 0)));
        JSONArray tagIds = source.optJSONArray("tag_ids");
        if (tagIds != null) {
            if (tagIds.length() > MAX_TAGS_PER_GALLERY) return null;
            JSONArray safeTagIds = new JSONArray();
            for (int index = 0; index < tagIds.length(); index++) {
                int tagId = tagIds.optInt(index, -1);
                if (tagId > 0) safeTagIds.put(tagId);
            }
            item.put("tag_ids", safeTagIds);
        }
        JSONArray tags = source.optJSONArray("tags");
        if (tags != null) {
            if (tags.length() > MAX_TAGS_PER_GALLERY) return null;
            JSONArray safeTags = new JSONArray();
            for (int index = 0; index < tags.length(); index++) {
                JSONObject tag = tags.optJSONObject(index);
                if (tag == null || tag.optInt("id", -1) <= 0) return null;
                String name = boundedString(tag, "name", MAX_TAG_FIELD_LENGTH);
                String type = boundedString(tag, "type", MAX_TAG_FIELD_LENGTH);
                if (name == null || type == null) return null;
                JSONObject safeTag = new JSONObject();
                safeTag.put("id", tag.optInt("id"));
                safeTag.put("name", name);
                safeTag.put("type", type);
                safeTag.put("count", Math.max(0, tag.optInt("count", 0)));
                safeTags.put(safeTag);
            }
            item.put("tags", safeTags);
        }
        return item;
    }

    private static void copyString(JSONObject source, JSONObject destination, String name, int maxLength)
        throws JSONException {
        String value = boundedString(source, name, maxLength);
        if (value != null) destination.put(name, value);
    }

    @Nullable
    private static String boundedString(JSONObject source, String name, int maxLength) {
        Object value = source.opt(name);
        if (!(value instanceof String)) return null;
        String text = (String) value;
        return text.length() <= maxLength ? text : null;
    }
}
