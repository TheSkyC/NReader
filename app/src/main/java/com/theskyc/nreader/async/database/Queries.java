// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.SimpleGallery;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GalleryData;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.components.TagList;
import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.api.enums.TitleType;
import com.theskyc.nreader.async.downloader.GalleryDownloaderManager;
import com.theskyc.nreader.async.downloader.GalleryDownloaderV2;
import com.theskyc.nreader.components.classes.Bookmark;
import com.theskyc.nreader.components.classes.ReadingPosition;
import com.theskyc.nreader.components.classes.ReaderBookmark;
import com.theskyc.nreader.components.status.Status;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;
import com.theskyc.nreader.utility.LogUtility;

import org.json.JSONException;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressLint("Range")
public class Queries {

    static SQLiteDatabase db;

    public static void setDb(SQLiteDatabase database) {
        db = database;
    }

    public static int getColumnFromName(Cursor cursor, String name) {
        return cursor.getColumnIndex(name);
    }

    /**
     * @noinspection unused
     */
    public static class DebugDatabase {
        private static void dumpTable(String name, FileWriter sb) throws IOException {

            String query = "SELECT * FROM " + name;
            sb.write("DUMPING: ");
            sb.write(name);
            try (Cursor c = db.rawQuery(query, null)) {
                sb.write(" count: ");
                sb.write("" + c.getCount());
                sb.write(": ");
                if (c.moveToFirst()) {
                    do {
                        sb.write(DatabaseUtils.dumpCurrentRowToString(c));
                    } while (c.moveToNext());
                }
            }
            sb.append("END DUMPING\n");
        }
    }

    /**
     * Table with information about the galleries
     */
    public static class GalleryTable {
        public static final String TABLE_NAME = "Gallery";
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        public static final String IDGALLERY = "idGallery";
        public static final String TITLE_ENG = "title_eng";
        public static final String TITLE_JP = "title_jp";
        public static final String TITLE_PRETTY = "title_pretty";
        public static final String FAVORITE_COUNT = "favorite_count";
        public static final String MEDIAID = "mediaId";
        public static final String FAVORITE = "favorite";
        public static final String PAGES = "pages";
        public static final String UPLOAD = "upload";
        public static final String MAX_WIDTH = "maxW";
        public static final String MAX_HEIGHT = "maxH";
        public static final String MIN_WIDTH = "minW";
        public static final String MIN_HEIGHT = "minH";
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Gallery` ( " +
            "`idGallery`      INT               NOT NULL PRIMARY KEY , " +
            "`title_eng`      TINYTEXT          NOT NULL, " +
            "`title_jp`       TINYTEXT          NOT NULL, " +
            "`title_pretty`   TINYTEXT          NOT NULL, " +
            "`favorite_count` INT               NOT NULL, " +
            "`mediaId`        INT               NOT NULL, " +
            "`pages`          TEXT              NOT NULL," +
            "`upload`         UNSIGNED BIG INT  NOT NULL," +//Date
            "`maxW`           INT               NOT NULL," +
            "`maxH`           INT               NOT NULL," +
            "`minW`           INT               NOT NULL," +
            "`minH`           INT               NOT NULL" +
            ");";

        static void clearGalleries() {
            db.delete(GalleryTable.TABLE_NAME, String.format(Locale.US,
                    "%s NOT IN (SELECT %s FROM %s) AND " +
                        "%s NOT IN (SELECT %s FROM %s) AND " +
                        "%s NOT IN (SELECT %s FROM %s)",
                    GalleryTable.IDGALLERY, DownloadTable.ID_GALLERY, DownloadTable.TABLE_NAME,
                    GalleryTable.IDGALLERY, FavoriteTable.ID_GALLERY, FavoriteTable.TABLE_NAME,
                    GalleryTable.IDGALLERY, StatusMangaTable.GALLERY, StatusMangaTable.TABLE_NAME)
                , null);
            db.delete(GalleryBridgeTable.TABLE_NAME, String.format(Locale.US,
                    "%s NOT IN (SELECT %s FROM %s)",
                    GalleryBridgeTable.ID_GALLERY, GalleryTable.IDGALLERY, GalleryTable.TABLE_NAME)
                , null);
            db.delete(FavoriteTable.TABLE_NAME, String.format(Locale.US,
                    "%s NOT IN (SELECT %s FROM %s)",
                    FavoriteTable.ID_GALLERY, GalleryTable.IDGALLERY, GalleryTable.TABLE_NAME)
                , null);
            db.delete(DownloadTable.TABLE_NAME, String.format(Locale.US,
                    "%s NOT IN (SELECT %s FROM %s)",
                    DownloadTable.ID_GALLERY, GalleryTable.IDGALLERY, GalleryTable.TABLE_NAME)
                , null);
            db.delete(ReaderBookmarkTable.TABLE_NAME, String.format(Locale.US,
                    "id_gallery NOT IN (SELECT %s FROM %s)", GalleryTable.IDGALLERY, GalleryTable.TABLE_NAME), null);
        }

        /**
         * Retrieve gallery using the id
         *
         * @param id id of the gallery to retrieve
         */
        public static Gallery galleryFromId(Context context, int id) {
            try (Cursor cursor = db.query(true, TABLE_NAME, null, IDGALLERY + "=?", new String[]{"" + id}, null, null, null, null)) {
                Gallery g = null;
                if (cursor.moveToFirst()) {
                    g = cursorToGallery(context, cursor);
                }
                return g;
            }
        }

        @Nullable
        public static Gallery completeCachedGalleryFromId(Context context, int id) {
            try (Cursor cursor = db.query(true, TABLE_NAME, null, IDGALLERY + "=?", new String[]{"" + id}, null, null, null, null)) {
                if (!cursor.moveToFirst()) return null;
                if (cursor.getInt(getColumnFromName(cursor, MEDIAID)) <= 0) return null;
                String path = cursor.getString(getColumnFromName(cursor, PAGES));
                if (path == null) return null;
                String[] parts = path.split(";", -1);
                if (parts.length < 5 || !parts[parts.length - 1].isEmpty()) return null;
                int count;
                try {
                    count = Integer.parseInt(parts[0]);
                } catch (NumberFormatException error) {
                    return null;
                }
                if (count <= 0 || count != parts.length - 4) return null;
                for (int index = 1; index < parts.length - 1; index++) {
                    if (!parts[index].startsWith("/") || parts[index].length() == 1) return null;
                }
                return cursorToGallery(context, cursor);
            }
        }

        /**
         * @return Cursor to favorite Table
         * @noinspection DeprecatedIsStillUsed
         * @deprecated This is only used to update an old database format to the newest one
         */
        @Deprecated
        @NonNull
        public static Cursor getAllFavoriteCursorDeprecated() {
            LogUtility.i("FILTER IN: %;;false");
            String sql = "SELECT * FROM " + TABLE_NAME + " WHERE (" +
                FAVORITE + " =? OR " + FAVORITE + "=3)";
            sql += " AND (" + TITLE_ENG + " LIKE ? OR " +
                TITLE_JP + " LIKE ? OR " +
                TITLE_PRETTY + " LIKE ? )";
            String q = "%%%";
            Cursor cursor = db.rawQuery(sql, new String[]{"1", q, q, q});
            LogUtility.d(sql);
            LogUtility.d("AFTER FILTERING: " + cursor.getCount());
            LogUtility.i("END FILTER IN: %;;false");
            return cursor;
        }

        /**
         * Retrieve all galleries inside the DB
         */
        public static List<Gallery> getAllGalleries(Context context) {
            String query = "SELECT * FROM " + TABLE_NAME;
            try (Cursor cursor = db.rawQuery(query, null)) {
                List<Gallery> galleries = new ArrayList<>(cursor.getCount());
                if (cursor.moveToFirst()) {
                    do {
                        galleries.add(cursorToGallery(context, cursor));
                    } while (cursor.moveToNext());
                }
                return galleries;
            }
        }

        public static void insert(GenericGallery gallery) {
            ContentValues values = new ContentValues(12);
            GalleryData data = gallery.getGalleryData();
            if (data.isIncomplete()) return;
            values.put(IDGALLERY, gallery.getId());
            values.put(TITLE_ENG, data.getTitle(TitleType.ENGLISH));
            values.put(TITLE_JP, data.getTitle(TitleType.JAPANESE));
            values.put(TITLE_PRETTY, data.getTitle(TitleType.PRETTY));
            values.put(FAVORITE_COUNT, data.getFavoriteCount());
            values.put(MEDIAID, data.getMediaId());
            values.put(PAGES, data.createPagePath());
            values.put(UPLOAD, data.getUploadDate().getTime());
            values.put(MAX_WIDTH, gallery.getMaxSize().getWidth());
            values.put(MAX_HEIGHT, gallery.getMaxSize().getHeight());
            values.put(MIN_WIDTH, gallery.getMinSize().getWidth());
            values.put(MIN_HEIGHT, gallery.getMinSize().getHeight());
            //Insert gallery
            db.insertWithOnConflict(TABLE_NAME, null, values, gallery instanceof Gallery ? SQLiteDatabase.CONFLICT_REPLACE : SQLiteDatabase.CONFLICT_IGNORE);
            TagTable.insertTagsForGallery(data);
        }

        static void insertMinimal(SimpleGallery gallery) {
            insertMinimal(gallery.getId(), gallery.getTitle(), gallery.getPageCount(),
                gallery.getMediaId(), gallery.getThumbnail().toString());
        }

        static void insertMinimal(Gallery gallery) {
            insertMinimal(gallery.getId(), gallery.getTitle(TitleType.PRETTY), gallery.getPageCount(),
                gallery.getMediaId(), gallery.getThumbnail().toString());
        }

        private static void insertMinimal(int id, String title, int pageCount, int mediaId, String thumbnail) {
            ContentValues values = new ContentValues(12);
            String pages = "partial-v1;" + Math.max(0, pageCount) + ";" + android.net.Uri.encode(thumbnail);
            values.put(IDGALLERY, id);
            values.put(TITLE_ENG, title);
            values.put(TITLE_JP, title);
            values.put(TITLE_PRETTY, title);
            values.put(FAVORITE_COUNT, 0);
            values.put(MEDIAID, mediaId);
            values.put(PAGES, pages);
            values.put(UPLOAD, 0L);
            values.put(MAX_WIDTH, 0);
            values.put(MAX_HEIGHT, 0);
            values.put(MIN_WIDTH, 0);
            values.put(MIN_HEIGHT, 0);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        /**
         * Convert a cursor pointing to galleries to a list of galleries, cursor not closed
         *
         * @param cursor Cursor to scroll
         * @return ArrayList of galleries
         */
        static List<Gallery> cursorToList(Context context, Cursor cursor) {
            List<Gallery> galleries = new ArrayList<>(cursor.getCount());
            if (cursor.moveToFirst()) {
                do {
                    galleries.add(GalleryTable.cursorToGallery(context, cursor));
                } while (cursor.moveToNext());
            }
            return galleries;
        }


        public static void delete(int id) {
            db.delete(TABLE_NAME, IDGALLERY + "=?", new String[]{"" + id});
            GalleryBridgeTable.deleteGallery(id);
            ReaderBookmarkTable.deleteForGallery(id);
        }


        /**
         * Convert a row of a cursor to a {@link Gallery}
         */
        public static Gallery cursorToGallery(Context context, Cursor cursor) {
            return new Gallery(context, cursor, GalleryBridgeTable.getTagsForGallery(cursor.getInt(getColumnFromName(cursor, IDGALLERY))));
        }

        /**
         * Insert max and min size of a certain {@link Gallery}
         */
        public static void updateSizes(@Nullable Gallery gallery) {
            if (gallery == null) return;
            ContentValues values = new ContentValues(4);
            values.put(MAX_WIDTH, gallery.getMaxSize().getWidth());
            values.put(MAX_HEIGHT, gallery.getMaxSize().getHeight());
            values.put(MIN_WIDTH, gallery.getMinSize().getWidth());
            values.put(MIN_HEIGHT, gallery.getMinSize().getHeight());
            db.updateWithOnConflict("Gallery", values, IDGALLERY + "=?", new String[]{"" + gallery.getId()}, SQLiteDatabase.CONFLICT_IGNORE);
        }


    }

    public static class TagTable {
        public static final String TABLE_NAME = "Tags";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Tags` (" +
            " `idTag` INT  NOT NULL PRIMARY KEY," +
            " `name` TEXT NOT NULL , " +
            "`type` TINYINT(1) NOT NULL , " +
            "`count` INT NOT NULL," +
            "`provisional` INTEGER NOT NULL DEFAULT 0);";
        static final String CREATE_SUGGESTION_INDEX = "CREATE INDEX IF NOT EXISTS Tags_type_name_suggestion_idx"
            + " ON Tags (`type`, `name` COLLATE NOCASE, `idTag`)";
        static final int SUGGESTION_LIMIT = 50;
        public static final String RULES_TABLE = "TagRules";
        static final String CREATE_RULES = "CREATE TABLE IF NOT EXISTS TagRules ("
            + "idTag INTEGER PRIMARY KEY CHECK (idTag > 0), name TEXT NOT NULL,"
            + " type INTEGER NOT NULL CHECK (type BETWEEN 1 AND 7),"
            + " status INTEGER NOT NULL CHECK (status IN (1, 2)))";
        static final String CREATE_ACCOUNT_BLACKLIST = "CREATE TABLE IF NOT EXISTS AccountTagBlacklist ("
            + "idTag INTEGER PRIMARY KEY CHECK (idTag > 0))";

        static final String IDTAG = "idTag";
        static final String NAME = "name";
        static final String TYPE = "type";
        static final String COUNT = "count";
        static final String STATUS = "status";
        private static final String METADATA_SELECT =
            "SELECT t.idTag, t.name, t.type, t.count, COALESCE(r.status, 0) AS status FROM Tags t"
                + " LEFT JOIN TagRules r ON t.idTag = r.idTag AND t.type = r.type";
        private static final String RULE_SELECT =
            "SELECT r.idTag, CASE WHEN t.type = r.type THEN t.name ELSE r.name END AS name, r.type,"
                + " CASE WHEN t.type = r.type THEN t.count ELSE 0 END AS count, r.status FROM TagRules r"
                + " LEFT JOIN Tags t ON t.idTag = r.idTag";
        private static final String COMPATIBLE_RULE = " (t.idTag IS NULL OR t.type = 0 OR t.type = r.type)";
        private static final String ONLINE_SELECT =
            "SELECT t.*, 1 AS status FROM AccountTagBlacklist b JOIN Tags t ON t.idTag = b.idTag";

        static void createTables(SQLiteDatabase database) {
            database.execSQL(CREATE_TABLE);
            database.execSQL(CREATE_SUGGESTION_INDEX);
            database.execSQL(CREATE_RULES);
            database.execSQL(CREATE_ACCOUNT_BLACKLIST);
        }

        /**
         * Convert a {@link Cursor} row to a {@link Tag}
         */
        public static Tag cursorToTag(Cursor cursor) {
            return new Tag(
                cursor.getString(cursor.getColumnIndex(NAME)),
                cursor.getInt(cursor.getColumnIndex(COUNT)),
                cursor.getInt(cursor.getColumnIndex(IDTAG)),
                TagType.values[cursor.getInt(cursor.getColumnIndex(TYPE))],
                TagStatus.values()[cursor.getInt(cursor.getColumnIndex(STATUS))]
            );
        }

        /**
         * Fetch all rows inside a {@link Cursor} and convert them into a {@link Tag}
         * The {@link Cursor} passed as parameter is closed
         */
        private static List<Tag> getTagsFromCursor(Cursor cursor) {
            try (cursor) {
                List<Tag> tags = new ArrayList<>(cursor.getCount());
                int i = 0;
                if (cursor.moveToFirst()) {
                    do {
                        tags.add(cursorToTag(cursor));
                    } while (cursor.moveToNext());
                }
                return tags;
            }
        }

        /**
         * Return a cursor which points to a list of {@link Tag} which have certain properties
         *
         * @param query      Retrieve only tags which contains a certain string
         * @param type       If not null only tags which are of a specific {@link TagType}
         * @param online     Retrieve only tags which have been blacklisted from the main site
         * @param sortByName sort by name or by count
         */
        public static Cursor getFilterCursor(@NonNull String query, TagType type, boolean online, boolean sortByName) {
            return getFilterCursor(query, type, online, sortByName, null);
        }

        public static Cursor getFilterCursor(@NonNull String query, TagType type, boolean online, boolean sortByName,
                                             @Nullable Locale appLocale) {
            String projection = online ? ONLINE_SELECT : type == null ? RULE_SELECT : METADATA_SELECT;
            String locale = TagTranslationCatalog.resolveInstalledLocale(db, appLocale);
            StringBuilder sql = new StringBuilder("SELECT tags.*, ");
            List<String> args = new ArrayList<>();
            sql.append(locale == null ? "NULL" : "tr.displayName").append(" AS displayName FROM (")
                .append(projection).append(") tags");
            if (locale != null) {
                sql.append(" LEFT JOIN TagTranslations tr ON tr.idTag = tags.idTag AND tr.locale = ?");
                if (!online && type == null) {
                    // A conflicting saved rule must not inherit the translation for a different tag type.
                    sql.append(" AND EXISTS (SELECT 1 FROM Tags t WHERE t.idTag = tags.idTag AND t.type = tags.type)");
                }
                args.add(locale);
            }
            sql.append(" WHERE 1");
            if (online && !Login.hasCurrentOnlineTags()) sql.append(" AND 0");
            if (!online && type != null) {
                sql.append(" AND count >= ?");
                args.add(Integer.toString(TagV2.getMinCount()));
            }
            if (!query.isEmpty()) {
                sql.append(" AND (tags.name LIKE ? ESCAPE '\\'");
                args.add("%" + escapeLikePrefix(query) + "%");
                if (locale != null) {
                    String pattern = "%" + escapeLikePrefix(TagTranslationCatalog.searchKey(query)) + "%";
                    sql.append(" OR tr.displayKey LIKE ? ESCAPE '\\'"
                        + " OR EXISTS (SELECT 1 FROM TagTranslationAliases"
                        + " WHERE idTag = tr.idTag AND locale = tr.locale AND aliasKey LIKE ? ESCAPE '\\')");
                    args.add(pattern);
                    args.add(pattern);
                }
                sql.append(")");
            }
            if (type != null) {
                sql.append(" AND type = ?");
                args.add(Byte.toString(type.getId()));
            }
            sql.append(sortByName ? " ORDER BY tags.name, tags.idTag" : " ORDER BY tags.count DESC, tags.name, tags.idTag");
            return db.rawQuery(sql.toString(), args.toArray(new String[0]));
        }

        /**
         * Returns a List of all tags of a specific type and which have a min count
         *
         * @param type The type to fetch
         */
        public static List<Tag> getAllTagOfType(TagType type) {
            String query = METADATA_SELECT + " WHERE t.type = ? AND t.count >= ?";
            return getTagsFromCursor(db.rawQuery(query, new String[]{"" + type.getId(), "" + TagV2.getMinCount()}));
        }

        public static List<TagSuggestion> getTagSuggestions(TagType type, String prefix, @Nullable String locale) {
            String canonicalPattern = escapeLikePrefix(prefix) + "%";
            String normalizedPrefix = TagTranslationCatalog.searchKey(prefix);
            String normalizedUpperBound = TagTranslationCatalog.prefixUpperBound(normalizedPrefix);
            boolean useTranslationRange = normalizedUpperBound != null;
            List<String> args = new ArrayList<>();
            args.add(Integer.toString(type.getId()));
            args.add(Integer.toString(TagV2.getMinCount()));
            args.add(canonicalPattern);
            if (locale != null) {
                String translationPrefix = useTranslationRange ? normalizedPrefix : escapeLikePrefix(normalizedPrefix) + "%";
                args.add(locale);
                args.add(translationPrefix);
                if (useTranslationRange) args.add(normalizedUpperBound);
                args.add(locale);
                args.add(translationPrefix);
                if (useTranslationRange) args.add(normalizedUpperBound);
            }
            args.add(locale == null ? "" : locale);
            args.add(Integer.toString(type.getId()));
            args.add(Integer.toString(TagV2.getMinCount()));
            try (Cursor cursor = db.rawQuery(
                buildTagSuggestionQuery(locale, useTranslationRange), args.toArray(new String[0]))) {
                List<TagSuggestion> suggestions = new ArrayList<>(cursor.getCount());
                if (cursor.moveToFirst()) {
                    do {
                        Tag tag = cursorToTag(cursor);
                        suggestions.add(new TagSuggestion(tag, cursor.getString(cursor.getColumnIndexOrThrow("displayName"))));
                    } while (cursor.moveToNext());
                }
                return suggestions;
            }
        }

        static String buildTagSuggestionQuery(@Nullable String locale, boolean useTranslationRange) {
            StringBuilder matches = new StringBuilder("SELECT idTag, 0 AS matchRank FROM Tags"
                + " WHERE type = ? AND count >= ? AND name COLLATE NOCASE LIKE ? ESCAPE '\\'");
            if (locale != null) {
                matches.append(" UNION ALL SELECT idTag, 1 FROM TagTranslations WHERE locale = ? AND ");
                appendTranslationPrefix(matches, "displayKey", useTranslationRange);
                matches.append(" UNION ALL SELECT idTag, 2 FROM TagTranslationAliases WHERE locale = ? AND ");
                appendTranslationPrefix(matches, "aliasKey", useTranslationRange);
            }
            return "SELECT t.idTag, t.name, t.type, t.count, COALESCE(r.status, 0) AS status,"
                + " COALESCE(tr.displayName, t.name) AS displayName, MIN(m.matchRank) AS matchRank"
                + " FROM (" + matches + ") m JOIN Tags t ON t.idTag = m.idTag"
                + " LEFT JOIN TagRules r ON r.idTag = t.idTag AND r.type = t.type"
                + " LEFT JOIN TagTranslations tr ON tr.idTag = t.idTag AND tr.locale = ?"
                + " WHERE t.type = ? AND t.count >= ? GROUP BY t.idTag"
                + " ORDER BY matchRank, displayName COLLATE NOCASE, t.idTag LIMIT " + SUGGESTION_LIMIT;
        }

        private static void appendTranslationPrefix(StringBuilder query, String column, boolean useRange) {
            if (useRange) query.append(column).append(" >= ? AND ").append(column).append(" < ?");
            else query.append(column).append(" LIKE ? ESCAPE '\\'");
        }

        public static List<TagSuggestion> getTagSuggestions(TagType type, String prefix, @Nullable Locale appLocale) {
            return getTagSuggestions(type, prefix, TagTranslationCatalog.resolveInstalledLocale(db, appLocale));
        }

        public static Map<Integer, String> getDisplayNames(List<Integer> siteIds, Locale appLocale) {
            return TagTranslationCatalog.getDisplayNames(db, siteIds, appLocale);
        }

        private static String escapeLikePrefix(String prefix) {
            return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }

        /**
         * Returns a List of all tags of a specific type
         *
         * @param type The type to fetch
         */
        public static List<Tag> getTrueAllType(TagType type) {
            String query = METADATA_SELECT + " WHERE t.type = ?";
            return getTagsFromCursor(db.rawQuery(query, new String[]{"" + type.getId()}));
        }

        /**
         * Returns a List of all tags of a specific status
         *
         * @param status The status to fetch
         */
        public static List<Tag> getAllStatus(TagStatus status) {
            if (status == TagStatus.DEFAULT) {
                return getTagsFromCursor(db.rawQuery(METADATA_SELECT + " WHERE r.idTag IS NULL", null));
            }
            return getEffectiveRules(db.rawQuery(RULE_SELECT + " WHERE r.status = ? AND" + COMPATIBLE_RULE,
                new String[]{Integer.toString(status.ordinal())}));
        }

        /**
         * Returns a List of all tags which are AVOIDED or ACCEPTED
         */
        public static List<Tag> getAllFiltered() {
            return getEffectiveRules(db.rawQuery(RULE_SELECT + " WHERE" + COMPATIBLE_RULE, null));
        }

        /**
         * Returns a List of all tags which are AVOIDED or ACCEPTED of a specific type
         */
        public static List<Tag> getAllFilteredByType(TagType type) {
            return getEffectiveRules(db.rawQuery(RULE_SELECT + " WHERE r.type = ? AND" + COMPATIBLE_RULE,
                new String[]{Byte.toString(type.getId())}));
        }

        private static List<Tag> getEffectiveRules(Cursor cursor) {
            List<Tag> rules = getTagsFromCursor(cursor);
            rules.removeIf(rule -> !TagCatalog.isCanonicalName(rule.getName()));
            return rules;
        }

        /**
         * Returns a List of all tags which have been blacklisted from the site
         */
        public static List<Tag> getAllOnlineBlacklisted() {
            String identity = Login.currentOnlineTagsIdentity();
            long revision = Login.currentOnlineTagsRevision();
            if (identity == null || revision < 0) return new ArrayList<>();
            List<Tag> t = getTagsFromCursor(db.rawQuery(ONLINE_SELECT, null));
            t.removeIf(tag -> !TagCatalog.isCanonicalName(tag.getName()));
            if (!identity.equals(Login.currentOnlineTagsIdentity())
                || revision != Login.currentOnlineTagsRevision()) return new ArrayList<>();
            return t;
        }

        /**
         * Returns true if the tag has been blacklisted form the main site
         */
        public static boolean isBlackListed(Tag tag) {
            String query = "SELECT 1 FROM AccountTagBlacklist b JOIN Tags t ON t.idTag = b.idTag"
                + " WHERE b.idTag = ? AND t.type = ?";
            try (Cursor c = db.rawQuery(query, new String[]{Integer.toString(tag.getId()), Byte.toString(tag.getType().getId())})) {
                return c.moveToFirst();
            }
        }

        /**
         * Returns the tag which has a specific if, null if it does not exists
         */
        @Nullable
        public static Tag getTagById(int id) {
            String query = METADATA_SELECT + " WHERE t.idTag = ?";
            try (Cursor c = db.rawQuery(query, new String[]{"" + id})) {
                Tag t = null;
                if (c.moveToFirst()) t = cursorToTag(c);
                return t;
            }
        }

        public static void updateStatus(int id, TagStatus status) {
            Tag tag = getTagById(id);
            if (tag != null) {
                tag.setStatus(status);
                updateTag(tag);
            }
        }

        /**
         * Persist a rule without replacing newer catalog metadata with values from the UI.
         */
        public static int updateTag(Tag tag) {
            if (tag.getId() <= 0 || tag.getType() == null || tag.getType().getId() <= 0) return 0;
            db.beginTransactionNonExclusive();
            try {
                if (!storeRule(tag, tag.getStatus())) return 0;
                db.setTransactionSuccessful();
                return 1;
            } finally {
                db.endTransaction();
            }
        }

        static TagStatus storedRuleStatus(Tag tag) {
            try (Cursor row = db.rawQuery("SELECT status FROM TagRules WHERE idTag = ? AND type = ?", ruleKey(tag))) {
                return row.moveToFirst() ? TagStatus.values()[row.getInt(0)] : TagStatus.DEFAULT;
            }
        }

        private static String[] ruleKey(Tag tag) {
            return new String[]{Integer.toString(tag.getId()), Byte.toString(tag.getType().getId())};
        }

        static boolean storeRule(Tag tag, TagStatus status) {
            String[] key = ruleKey(tag);
            if (status == TagStatus.DEFAULT) {
                db.delete(RULES_TABLE, "idTag = ? AND type = ?", key);
                return true;
            }
            String name;
            try (Cursor row = db.rawQuery("SELECT name FROM ("
                + " SELECT name, 0 AS priority FROM Tags WHERE idTag = ? AND type = ?"
                + " UNION ALL SELECT name, 1 AS priority FROM TagRules WHERE idTag = ? AND type = ?"
                + ") ORDER BY priority LIMIT 1", new String[]{key[0], key[1], key[0], key[1]})) {
                if (row.moveToFirst()) name = row.getString(0);
                else if (TagCatalog.isCanonicalName(tag.getName()) && !hasConflictingCatalogType(key)) {
                    name = tag.getName();
                }
                else return false;
            }
            ContentValues rule = new ContentValues(4);
            rule.put(IDTAG, tag.getId());
            rule.put(NAME, name);
            rule.put(TYPE, tag.getType().getId());
            rule.put(STATUS, status.ordinal());
            return db.insertWithOnConflict(RULES_TABLE, null, rule, SQLiteDatabase.CONFLICT_REPLACE) != -1;
        }

        private static boolean hasConflictingCatalogType(String[] key) {
            try (Cursor row = db.rawQuery("SELECT 1 FROM Tags WHERE idTag = ? AND type > 0 AND type != ?", key)) {
                return row.moveToFirst();
            }
        }

        public static void insert(Tag tag) {
            if (tag.getId() <= 0 || tag.getType() == null || !TagCatalog.isCanonicalName(tag.getName())) return;
            ContentValues values = new ContentValues(4);
            values.put(IDTAG, tag.getId());
            values.put(NAME, tag.getName());
            values.put(TYPE, tag.getType().getId());
            values.put(COUNT, Math.max(0, tag.getCount()));
            if (db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1
                && tag.getType().getId() > 0) {
                values.put("provisional", 0);
                db.update(TABLE_NAME, values, "idTag = ? AND (provisional = 1 OR type = 0)",
                    new String[]{Integer.toString(tag.getId())});
            }
        }

        public static void updateBlacklistedTag(Tag tag, boolean online) {
            db.beginTransactionNonExclusive();
            try {
                if (online) insertBlacklistedTag(tag);
                else db.delete("AccountTagBlacklist", "idTag = ?", new String[]{Integer.toString(tag.getId())});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        private static void insertBlacklistedTag(Tag tag) {
            if (tag.getType() == null || tag.getType().getId() <= 0 || !TagCatalog.isCanonicalName(tag.getName())) return;
            insert(tag);
            db.execSQL("INSERT OR IGNORE INTO AccountTagBlacklist (idTag)"
                + " SELECT idTag FROM Tags WHERE idTag = ? AND type = ? AND idTag > 0",
                new Object[]{tag.getId(), tag.getType().getId()});
        }

        public static void replaceOnlineTags(@NonNull List<Tag> tags) {
            db.beginTransaction();
            try {
                db.delete("AccountTagBlacklist", null, null);
                for (Tag tag : tags) insertBlacklistedTag(tag);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        public static void resetAllStatus() {
            db.delete(RULES_TABLE, null, null);
        }

        /**
         * Get the first <code>count</code> tags of <code>type</code>, ordered by tag count
         */
        public static List<Tag> getTopTags(TagType type, int count) {
            String query = METADATA_SELECT + " WHERE t.type = ? ORDER BY t.count DESC LIMIT ?";
            Cursor cursor = db.rawQuery(query, new String[]{"" + type.getId(), "" + count});
            return TagTable.getTagsFromCursor(cursor);
        }

        /**
         * Retrieve the status of a tag from the DB and set it
         *
         * @return the status if the tag exists, null otherwise
         */
        @Nullable
        public static TagStatus getStatus(Tag tag) {
            String query = "SELECT status FROM (" + RULE_SELECT + " WHERE" + COMPATIBLE_RULE
                + " UNION ALL " + METADATA_SELECT + " WHERE r.idTag IS NULL) WHERE idTag = ? AND type = ?";
            try (Cursor c = db.rawQuery(query, new String[]{Integer.toString(tag.getId()), Byte.toString(tag.getType().getId())})) {
                TagStatus status = null;
                if (c.moveToFirst()) {
                    status = TagStatus.values()[c.getInt(0)];
                    tag.setStatus(status);
                }
                return status;
            }
        }

        public static Tag getTagFromTagName(String name) {
            Tag tag = null;
            try (Cursor cursor = db.rawQuery(METADATA_SELECT + " WHERE t.name = ?", new String[]{name})) {
                if (cursor.moveToFirst()) tag = cursorToTag(cursor);
                return tag;
            }
        }

        /**
         * @param tagString a comma-separated list of integers (maybe vulnerable)
         * @return the tags with id contained inside the list
         */
        public static TagList getTagsFromListOfInt(String tagString) {
            TagList tags = new TagList();
            String query = METADATA_SELECT + " WHERE t.idTag IN (" + tagString + ")";
            try (Cursor cursor = db.rawQuery(query, null)) {
                if (cursor.moveToFirst()) {
                    do {
                        tags.addTag(cursorToTag(cursor));
                    } while (cursor.moveToNext());
                }
            }
            return tags;
        }

        /**
         * Return a list of tags which contain name and are of a certain type
         */
        public static List<Tag> search(String name, TagType type) {
            String query = METADATA_SELECT + " WHERE t.name LIKE ? AND t.type = ?";
            LogUtility.d(query);
            Cursor c = db.rawQuery(query, new String[]{'%' + name + '%', "" + type.getId()});
            return getTagsFromCursor(c);
        }

        /**
         * Search a tag by name and type
         *
         * @return The Tag if found, null otehrwise
         */
        public static Tag searchTag(String name, TagType type) {
            Tag tag = null;
            String query = METADATA_SELECT + " WHERE t.name = ? AND t.type = ?";
            LogUtility.d(query);
            try (Cursor c = db.rawQuery(query, new String[]{name, "" + type.getId()})) {
                if (c.moveToFirst()) tag = cursorToTag(c);
                return tag;
            }
        }

        /**
         * Insert all tags owned by a gallery and link it using {@link GalleryBridgeTable}
         */
        public static void insertTagsForGallery(GalleryData gallery) {
            TagList tags = gallery.getTags();
            int len;
            Tag tag;
            for (TagType t : TagType.values) {
                len = tags.getCount(t);
                for (int i = 0; i < len; i++) {
                    tag = tags.getTag(t, i);
                    TagTable.insert(tag);//Insert tag
                    GalleryBridgeTable.insert(gallery.getId(), tag.getId());//Insert link
                }
            }
        }
    }

    public static class DownloadTable {
        public static final String ID_GALLERY = "id_gallery";
        public static final String RANGE_START = "range_start";
        public static final String RANGE_END = "range_end";
        public static final String TABLE_NAME = "Downloads";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Downloads` (" +
            "`id_gallery`  INT NOT NULL PRIMARY KEY , " +
            "`range_start` INT NOT NULL," +
            "`range_end`   INT NOT NULL," +
            "FOREIGN KEY(`id_gallery`) REFERENCES `Gallery`(`idGallery`) ON UPDATE CASCADE ON DELETE CASCADE" +
            "); ";

        public static void addGallery(GalleryDownloaderV2 downloader) {
            Gallery gallery = downloader.getGallery();
            Queries.GalleryTable.insert(gallery);
            ContentValues values = new ContentValues(3);
            values.put(ID_GALLERY, gallery.getId());
            values.put(RANGE_START, downloader.getStart());
            values.put(RANGE_END, downloader.getEnd());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        public static void removeGallery(int id) {
            db.delete(TABLE_NAME, ID_GALLERY + "=?", new String[]{"" + id});
        }

        public static List<GalleryDownloaderManager> getAllDownloads(Context context) {
            String q = "SELECT * FROM %s INNER JOIN %s ON %s=%s";
            String query = String.format(Locale.US, q, GalleryTable.TABLE_NAME, DownloadTable.TABLE_NAME, GalleryTable.IDGALLERY, DownloadTable.ID_GALLERY);
            try (Cursor c = db.rawQuery(query, null)) {
                List<GalleryDownloaderManager> managers = new ArrayList<>();

                Gallery x;
                GalleryDownloaderManager m;
                if (c.moveToFirst()) {
                    do {
                        x = GalleryTable.cursorToGallery(context, c);
                        m = new GalleryDownloaderManager(context, x, c.getInt(c.getColumnIndex(RANGE_START)), c.getInt(c.getColumnIndex(RANGE_END)));
                        managers.add(m);
                    } while (c.moveToNext());
                }
                return managers;
            }
        }
    }

    public static class HistoryTable {
        public static final String ID = "id";
        public static final String MEDIAID = "mediaId";
        public static final String TITLE = "title";
        public static final String THUMB = "thumbType";
        public static final String TIME = "time";
        public static final String TABLE_NAME = "History";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `History`(" +
            "`id` INT NOT NULL PRIMARY KEY," +
            "`mediaId` INT NOT NULL," +
            "`title` TEXT NOT NULL," +
            "`thumbType` TINYINT(1) NOT NULL," +
            "`time` INT NOT NULL" +
            ");";

        public static void addGallery(SimpleGallery gallery) {
            if (gallery.getId() <= 0) return;
            ContentValues values = new ContentValues(5);
            values.put(ID, gallery.getId());
            values.put(MEDIAID, gallery.getMediaId());
            values.put(TITLE, gallery.getTitle());
            values.put(THUMB, gallery.getThumbnail().toString());
            values.put(TIME, new Date().getTime());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            cleanHistory();
        }

        public static List<SimpleGallery> getHistory() {
            ArrayList<SimpleGallery> galleries = new ArrayList<>();
            try (Cursor c = db.query(TABLE_NAME, null, null, null, null, null, TIME + " DESC", "" + Global.getMaxHistory())) {
                if (c.moveToFirst()) {
                    do {
                        galleries.add(new SimpleGallery(c));
                    } while (c.moveToNext());
                }
                galleries.trimToSize();
            }
            return galleries;
        }

        public static void emptyHistory() {
            db.delete(TABLE_NAME, null, null);
        }

        private static void cleanHistory() {
            //noinspection StatementWithEmptyBody
            while (db.delete(TABLE_NAME, "(SELECT COUNT(*) FROM " + TABLE_NAME + ")>? AND " + TIME + "=(SELECT MIN(" + TIME + ") FROM " + TABLE_NAME + ")", new String[]{"" + Global.getMaxHistory()}) == 1)
                ;
        }
    }

    public static class BookmarkTable {
        public static final String TABLE_NAME = "Bookmark";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String URL = "url";
        static final String PAGE = "page";
        static final String TYPE = "type";
        static final String TAG_ID = "tagId";
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Bookmark`(" +
            "`url` TEXT NOT NULL UNIQUE," +
            "`page` INT NOT NULL," +
            "`type` INT NOT NULL," +
            "`tagId` INT NOT NULL" +
            ");";

        public static void deleteBookmark(String url) {
            LogUtility.d("Deleted: " + db.delete(TABLE_NAME, URL + "=?", new String[]{url}));
        }

        public static void addBookmark(InspectorV3 inspector) {
            Tag tag = inspector.getTag();
            ContentValues values = new ContentValues(4);
            values.put(URL, inspector.getUrl());
            values.put(PAGE, inspector.getPage());
            values.put(TYPE, inspector.getRequestType().ordinal());
            values.put(TAG_ID, tag == null ? 0 : tag.getId());
            LogUtility.d("ADDED: " + inspector.getUrl());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        public static List<Bookmark> getBookmarks() {
            String query = "SELECT * FROM " + TABLE_NAME;
            try (Cursor cursor = db.rawQuery(query, null)) {
                List<Bookmark> bookmarks = new ArrayList<>(cursor.getCount());
                Bookmark b;
                LogUtility.d("This url has " + cursor.getCount());
                if (cursor.moveToFirst()) {
                    do {
                        b = new Bookmark(
                            cursor.getString(cursor.getColumnIndex(URL)),
                            cursor.getInt(cursor.getColumnIndex(PAGE)),
                            ApiRequestType.values[cursor.getInt(cursor.getColumnIndex(TYPE))],
                            cursor.getInt(cursor.getColumnIndex(TAG_ID))
                        );
                        bookmarks.add(b);
                    } while (cursor.moveToNext());
                }
                return bookmarks;
            }
        }
    }

    public static class GalleryBridgeTable {
        public static final String TABLE_NAME = "GalleryTags";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `GalleryTags` (" +
            "`id_gallery` INT NOT NULL , " +
            "`id_tag` INT NOT NULL ," +
            "PRIMARY KEY (`id_gallery`, `id_tag`), " +
            "FOREIGN KEY(`id_gallery`) REFERENCES `Gallery`(`idGallery`) ON UPDATE CASCADE ON DELETE CASCADE , " +
            "FOREIGN KEY(`id_tag`) REFERENCES `Tags`(`idTag`) ON UPDATE CASCADE ON DELETE RESTRICT );";

        static final String ID_GALLERY = "id_gallery";
        static final String ID_TAG = "id_tag";

        static void insert(int galleryId, int tagId) {
            ContentValues values = new ContentValues(2);
            values.put(ID_GALLERY, galleryId);
            values.put(ID_TAG, tagId);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        public static void deleteGallery(int id) {
            db.delete(TABLE_NAME, ID_GALLERY + "=?", new String[]{"" + id});
        }

        static Cursor getTagCursorForGallery(int id) {
            return db.rawQuery(TagTable.METADATA_SELECT
                + " WHERE t.idTag IN (SELECT id_tag FROM GalleryTags WHERE id_gallery = ?)",
                new String[]{Integer.toString(id)});
        }

        public static TagList getTagsForGallery(int id) {
            Cursor c = getTagCursorForGallery(id);
            TagList tagList = new TagList();
            List<Tag> tags = TagTable.getTagsFromCursor(c);
            tagList.addTags(tags);
            return tagList;
        }
    }

    public static class FavoriteTable {
        public static final String TABLE_NAME = "Favorite";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Favorite` (" +
            "`id_gallery` INT NOT NULL PRIMARY KEY , " +
            "`time` INT NOT NULL," +
            "FOREIGN KEY(`id_gallery`) REFERENCES `Gallery`(`idGallery`) ON UPDATE CASCADE ON DELETE CASCADE);";

        static final String ID_GALLERY = "id_gallery";
        static final String TIME = "time";

        private static final String TITLE_CLAUSE = String.format(Locale.US, "%s LIKE ? OR %s LIKE ? OR %s LIKE ?",
            GalleryTable.TITLE_ENG,
            GalleryTable.TITLE_JP,
            GalleryTable.TITLE_PRETTY
        );

        private static final String FAVORITE_JOIN_GALLERY = String.format(Locale.US, "%s INNER JOIN %s ON %s=%s",
            FavoriteTable.TABLE_NAME,
            GalleryTable.TABLE_NAME,
            FavoriteTable.ID_GALLERY,
            GalleryTable.IDGALLERY
        );

        public static void addFavorite(Gallery gallery) {
            addFavorite((GenericGallery) gallery);
        }

        public static void addFavorite(GenericGallery gallery) {
            if (gallery == null || gallery.getId() <= 0) throw new IllegalArgumentException("Invalid gallery for local favorite");
            db.beginTransaction();
            try {
                if (gallery instanceof SimpleGallery) GalleryTable.insertMinimal((SimpleGallery) gallery);
                else if (gallery instanceof Gallery && gallery.getGalleryData().isIncomplete())
                    GalleryTable.insertMinimal((Gallery) gallery);
                else if (gallery.hasGalleryData()) GalleryTable.insert(gallery);
                else throw new IllegalArgumentException("Gallery has no data for local favorite");
                try (Cursor cursor = db.query(GalleryTable.TABLE_NAME, new String[]{GalleryTable.IDGALLERY},
                    GalleryTable.IDGALLERY + "=?", new String[]{Integer.toString(gallery.getId())}, null, null, null)) {
                    if (!cursor.moveToFirst()) throw new android.database.SQLException("Unable to save favorite gallery");
                }
                FavoriteTable.insert(gallery.getId());
                if (!FavoriteTable.isFavorite(gallery.getId()))
                    throw new android.database.SQLException("Unable to save local favorite");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }


        static String titleTypeToColumn(TitleType type) {
            switch (type) {
                case PRETTY:
                    return GalleryTable.TITLE_PRETTY;
                case ENGLISH:
                    return GalleryTable.TITLE_ENG;
                case JAPANESE:
                    return GalleryTable.TITLE_JP;
            }
            return "";
        }

        /**
         * Get all favorites galleries which title contains <code>query</code>
         *
         * @param orderByTitle true if order by title, false order by latest
         * @return cursor which points to the galleries
         */
        public static Cursor getAllFavoriteGalleriesCursor(CharSequence query, boolean orderByTitle, int limit, int offset) {
            String order = orderByTitle ? titleTypeToColumn(Global.getTitleType()) : FavoriteTable.TIME + " DESC";
            String param = "%" + query + "%";
            String limitString = String.format(Locale.US, " %d, %d ", offset, limit);
            return db.query(FAVORITE_JOIN_GALLERY, null, TITLE_CLAUSE, new String[]{param, param, param}, null, null, order, limitString);
        }

        /**
         * Get all favorites galleries
         *
         * @return cursor which points to the galleries
         */
        public static Cursor getAllFavoriteGalleriesCursor() {
            String query = String.format(Locale.US, "SELECT * FROM %s WHERE %s IN (SELECT %s FROM %s)",
                GalleryTable.TABLE_NAME,
                GalleryTable.IDGALLERY,
                FavoriteTable.ID_GALLERY,
                FavoriteTable.TABLE_NAME
            );
            return db.rawQuery(query, null);
        }

        /**
         * Retrieve all favorite galleries
         */
        static List<Gallery> getAllFavoriteGalleries(Context context) {
            try (Cursor c = getAllFavoriteGalleriesCursor()) {
                return GalleryTable.cursorToList(context, c);
            }
        }

        static void insert(int galleryId) {
            ContentValues values = new ContentValues(2);
            values.put(ID_GALLERY, galleryId);
            values.put(TIME, new Date().getTime());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            GalleryStateRepository.invalidate();
        }

        public static void removeFavorite(int id) {
            db.delete(TABLE_NAME, ID_GALLERY + "=?", new String[]{"" + id});
            GalleryStateRepository.invalidate();
        }

        public static int countFavorite(@Nullable String text) {
            if (text == null || text.trim().isEmpty()) return countFavorite();
            int totalFavorite = 0;
            String param = "%" + text + "%";
            try (Cursor c = db.query(FAVORITE_JOIN_GALLERY, new String[]{"COUNT(*)"}, TITLE_CLAUSE, new String[]{param, param, param}, null, null, null)) {
                if (c.moveToFirst()) {
                    totalFavorite = c.getInt(0);
                }
            }
            return totalFavorite;
        }

        public static int countFavorite() {
            int totalFavorite = 0;
            String query = "SELECT COUNT(*) FROM " + TABLE_NAME;
            try (Cursor c = db.rawQuery(query, null)) {
                if (c.moveToFirst()) {
                    totalFavorite = c.getInt(0);
                }
                return totalFavorite;
            }
        }

        public static boolean isFavorite(int id) {
            String query = "SELECT * FROM " + TABLE_NAME + " WHERE " + ID_GALLERY + "=?";
            try (Cursor c = db.rawQuery(query, new String[]{"" + id})) {
                return c.moveToFirst();
            }
        }

        public static void removeAllFavorite() {
            db.delete(TABLE_NAME, null, null);
            GalleryStateRepository.invalidate();
        }
    }

    public static class ReaderBookmarkTable {
        public static final String TABLE_NAME = "ReaderBookmarks";
        public static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `ReaderBookmarks` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "`id_gallery` INT NOT NULL CHECK (`id_gallery` > 0), " +
            "`page` INT NOT NULL CHECK (`page` > 0), " +
            "`title` TEXT NOT NULL, " +
            "`note` TEXT NOT NULL DEFAULT '', " +
            "`created_at` INT NOT NULL, " +
            "`updated_at` INT NOT NULL, " +
            "UNIQUE (`id_gallery`, `page`)" +
            ");";

        private static final String[] COLUMNS = {
            "id", "id_gallery", "page", "title", "note", "created_at", "updated_at"
        };

        @NonNull
        public static List<ReaderBookmark> getForGallery(int galleryId) {
            List<ReaderBookmark> result = new ArrayList<>();
            if (galleryId <= 0) return result;
            try (Cursor cursor = db.query(TABLE_NAME, COLUMNS, "id_gallery=?",
                new String[]{Integer.toString(galleryId)}, null, null, "page ASC, updated_at DESC")) {
                if (cursor.moveToFirst()) {
                    do {
                        result.add(fromCursor(cursor));
                    } while (cursor.moveToNext());
                }
            }
            return result;
        }

        public static long upsert(int galleryId, int page, @Nullable String title, @Nullable String note) {
            if (galleryId <= 0 || page <= 0) return -1;
            long now = System.currentTimeMillis();
            String safeTitle = normalize(title, 200, "Page " + page);
            String safeNote = normalize(note, 2000, "");
            ContentValues values = new ContentValues(6);
            values.put("id_gallery", galleryId);
            values.put("page", page);
            values.put("title", safeTitle);
            values.put("note", safeNote);
            values.put("created_at", now);
            values.put("updated_at", now);
            return db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        public static void delete(long id) {
            if (id > 0) db.delete(TABLE_NAME, "id=?", new String[]{Long.toString(id)});
        }

        public static void deleteForGallery(int galleryId) {
            if (galleryId > 0) db.delete(TABLE_NAME, "id_gallery=?", new String[]{Integer.toString(galleryId)});
        }

        @NonNull
        private static ReaderBookmark fromCursor(@NonNull Cursor cursor) {
            return new ReaderBookmark(
                cursor.getLong(0), cursor.getInt(1), cursor.getInt(2),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getLong(6));
        }

        @NonNull
        private static String normalize(@Nullable String value, int maxLength, @NonNull String fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
        }
    }

    public static class ReadingProgressTable {
        public static final String TABLE_NAME = "ReadingProgress";
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `ReadingProgress` (" +
            "`id_gallery` INT NOT NULL PRIMARY KEY CHECK (`id_gallery` > 0), " +
            "`page` INT NOT NULL CHECK (`page` > 0), " +
            "`offset` REAL NOT NULL DEFAULT 0 CHECK (`offset` >= 0 AND `offset` < 1), " +
            "`updated_at` INT NOT NULL);";

        public static void insert(int id, ReadingPosition position) {
            if (id <= 0) return;
            ContentValues values = new ContentValues(4);
            values.put("id_gallery", id);
            values.put("page", position.page);
            values.put("offset", position.offset);
            values.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            GalleryStateRepository.invalidate();
        }

        @Nullable
        public static ReadingPosition fromId(int id) {
            try (Cursor cursor = db.query(TABLE_NAME, new String[]{"page", "offset"},
                "id_gallery=?", new String[]{Integer.toString(id)}, null, null, null)) {
                return cursor.moveToFirst() ? new ReadingPosition(cursor.getInt(0), cursor.getFloat(1)) : null;
            }
        }
    }

    public static class ResumeTable {
        public static final String TABLE_NAME = "Resume";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Resume` (" +
            "`id_gallery` INT NOT NULL PRIMARY KEY , " +
            "`page` INT NOT NULL" +
            ");";
        static final String ID_GALLERY = "id_gallery";
        static final String PAGE = "page";

        public static void insert(int id, int page) {
            if (id < 0) return;
            ContentValues values = new ContentValues(2);
            values.put(ID_GALLERY, id);
            values.put(PAGE, page);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            LogUtility.d("Added bookmark to page " + page + " of id " + id);
        }

        public static int pageFromId(int id) {
            if (id < 0) return -1;
            int val = -1;
            try (Cursor c = db.query(TABLE_NAME, new String[]{PAGE}, ID_GALLERY + "= ?", new String[]{"" + id}, null, null, null)) {
                if (c.moveToFirst())
                    val = c.getInt(c.getColumnIndex(PAGE));
                return val;
            }
        }

        public static void remove(int id) {
            db.delete(TABLE_NAME, ID_GALLERY + "= ?", new String[]{"" + id});
        }

    }

    public static class StatusMangaTable {
        public static final String TABLE_NAME = "StatusManga";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `StatusManga` (" +
            "`gallery` INT NOT NULL PRIMARY KEY, " +
            "`name` TINYTEXT NOT NULL, " +
            "`time` INT NOT NULL," +
            "FOREIGN KEY(`gallery`) REFERENCES `" + GalleryTable.TABLE_NAME + "`(`" + GalleryTable.IDGALLERY + "`) ON UPDATE CASCADE ON DELETE CASCADE," +
            "FOREIGN KEY(`name`) REFERENCES `" + StatusTable.TABLE_NAME + "`(`" + StatusTable.NAME + "`) ON UPDATE CASCADE ON DELETE CASCADE" +
            ");";
        static final String NAME = "name";
        static final String GALLERY = "gallery";
        static final String TIME = "time";

        public static void insert(GenericGallery gallery, Status status) {
            ContentValues values = new ContentValues(3);
            GalleryTable.insert(gallery);
            StatusTable.insert(status);
            values.put(NAME, status.name);
            values.put(GALLERY, gallery.getId());
            values.put(TIME, new Date().getTime());
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            GalleryStateRepository.invalidate();
        }

        public static void remove(int id) {
            db.delete(TABLE_NAME, GALLERY + "=?", new String[]{"" + id});
            GalleryStateRepository.invalidate();
        }

        @NonNull
        public static Status getStatus(int id) {
            try (Cursor cursor = db.query(TABLE_NAME, new String[]{NAME}, GALLERY + "=?", new String[]{"" + id}, null, null, null)) {
                Status status;
                if (cursor.moveToFirst())
                    status = StatusManager.getByName(cursor.getString(cursor.getColumnIndex(NAME)));
                else
                    status = StatusManager.getByName(StatusManager.DEFAULT_STATUS);
                return status == null ? StatusManager.getByName(StatusManager.DEFAULT_STATUS) : status;
            }
        }

        public static void insert(GenericGallery gallery, String s) {
            Status status = StatusManager.getByName(s);
            if (status != null) insert(gallery, status);
        }

        public static void update(Status oldStatus, Status newStatus) {
            ContentValues values = new ContentValues(1);
            values.put(NAME, newStatus.name);
            values.put(TIME, new Date().getTime());
            db.update(TABLE_NAME, values, NAME + "=?", new String[]{oldStatus.name});
        }

        public static Cursor getGalleryOfStatus(String name, String filter, boolean sortByTitle) {
            String query = String.format("SELECT * FROM %s INNER JOIN %s ON %s=%s WHERE %s=? AND (%s LIKE ? OR %s LIKE ? OR %s LIKE ?) ORDER BY %s",
                GalleryTable.TABLE_NAME, StatusMangaTable.TABLE_NAME,
                GalleryTable.IDGALLERY, StatusMangaTable.GALLERY,
                StatusMangaTable.NAME,
                GalleryTable.TITLE_ENG, GalleryTable.TITLE_JP, GalleryTable.TITLE_PRETTY,
                sortByTitle ? FavoriteTable.titleTypeToColumn(Global.getTitleType()) : TIME + " DESC"
            );
            String likeFilter = '%' + filter + '%';
            LogUtility.d(query);
            return db.rawQuery(query, new String[]{name, likeFilter, likeFilter, likeFilter});
        }

        public static int getCountPerStatus(String name) {
            String query = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?",
                StatusMangaTable.TABLE_NAME,
                StatusMangaTable.NAME);
            LogUtility.d(query);
            int value = -1;
            try (Cursor cursor = db.rawQuery(query, new String[]{name})) {
                if (cursor.moveToFirst()) {
                    value = cursor.getInt(0);
                }
                return value;
            }
        }

        public static void removeStatus(String name) {
            db.delete(TABLE_NAME, NAME + "=?", new String[]{name});
            GalleryStateRepository.invalidate();
        }
    }

    public static class StatusTable {
        public static final String TABLE_NAME = "Status";
        /**
         * @noinspection unused
         */
        public static final String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
        static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `Status` (" +
            "`name` TINYTEXT NOT NULL PRIMARY KEY, " +
            "`color` INT NOT NULL " +
            ");";
        static final String NAME = "name";
        static final String COLOR = "color";

        public static void insert(Status status) {
            ContentValues values = new ContentValues(2);
            values.put(NAME, status.name);
            values.put(COLOR, status.color);
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        public static void remove(String name) {
            db.delete(TABLE_NAME, NAME + "= ?", new String[]{name});
            StatusMangaTable.removeStatus(name);
        }

        public static void initStatuses() {
            List<Status> statuses = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME, null)) {
                if (cursor.moveToFirst()) {
                    do {
                        statuses.add(new Status(cursor.getInt(cursor.getColumnIndex(COLOR)),
                            cursor.getString(cursor.getColumnIndex(NAME))));
                    } while (cursor.moveToNext());
                }
            }
            StatusManager.replaceAll(statuses);
        }

        public static void update(Status oldStatus, Status newStatus) {
            ContentValues values = new ContentValues(2);
            values.put(NAME, newStatus.name);
            values.put(COLOR, newStatus.color);
            db.update(TABLE_NAME, values, NAME + "=?", new String[]{oldStatus.name});
            StatusMangaTable.update(oldStatus, newStatus);
            GalleryStateRepository.invalidate();
        }
    }
}
