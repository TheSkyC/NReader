package com.theskyc.nreader.async.database.export;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagCatalog;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** The portable rule and library-reference portion of the backup. */
final class BackupTags {
    static final String REFERENCES = "TagReferences";
    static final String RULES = Queries.TagTable.RULES_TABLE;
    static final String LEGACY = Queries.TagTable.TABLE_NAME;
    private static final String STAGE = "temp.RestoreTagReferences";
    private static final String REFERENCED_IDS =
        "SELECT id_tag FROM GalleryTags UNION SELECT tagId FROM Bookmark WHERE tagId > 0";

    private BackupTags() {}

    static boolean isTagSection(String table) {
        return REFERENCES.equals(table) || RULES.equals(table) || LEGACY.equals(table);
    }

    static Cursor exportRows(SQLiteDatabase db, String table) {
        switch (table) {
            case REFERENCES:
                return db.rawQuery("SELECT idTag, name, type, MAX(count, 0) AS count FROM Tags WHERE idTag > 0 AND idTag IN ("
                    + REFERENCED_IDS + ") ORDER BY idTag", null);
            case RULES:
                return db.rawQuery("SELECT r.idTag, COALESCE(t.name, r.name) AS name, r.type, r.status"
                    + " FROM TagRules r LEFT JOIN Tags t ON t.idTag = r.idTag AND t.type = r.type"
                    + " ORDER BY r.idTag", null);
            default:
                return db.query(table, null, null, null, null, null, null);
        }
    }

    static void readRows(SQLiteDatabase db, String section, JsonReader reader) throws IOException {
        boolean legacy = LEGACY.equals(section);
        boolean rules = !REFERENCES.equals(section);
        boolean references = !RULES.equals(section);
        if (rules) db.delete(RULES, null, null);
        if (references) db.execSQL("CREATE TEMP TABLE RestoreTagReferences ("
            + "idTag INTEGER PRIMARY KEY, name TEXT NOT NULL, type INTEGER NOT NULL, count INTEGER NOT NULL)");
        try (SQLiteStatement rule = db.compileStatement("INSERT INTO TagRules VALUES (?, ?, ?, ?)");
             SQLiteStatement reference = references
                 ? db.compileStatement("INSERT INTO " + STAGE + " VALUES (?, ?, ?, ?)") : null) {
            reader.beginArray();
            while (reader.hasNext()) {
                int id = 0;
                int type = -1;
                int count = -1;
                int status = -1;
                String name = null;
                Set<String> fields = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    if (!fields.add(field)) throw new IOException("Duplicate tag field");
                    switch (field) {
                        case "idTag": id = readInt(reader); break;
                        case "type": type = readInt(reader); break;
                        case "count":
                            if (!references) throw new IOException("Unexpected tag rule count");
                            count = readInt(reader);
                            break;
                        case "status":
                            if (!rules) throw new IOException("Unexpected tag reference status");
                            status = readInt(reader);
                            break;
                        case "online":
                            if (!legacy) throw new IOException("Account state is not portable");
                            BackupJson.skipValue(reader);
                            break;
                        case "name":
                            if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid backup tag name");
                            name = reader.nextString();
                            break;
                        default: throw new IOException("Unknown backup tag field");
                    }
                }
                reader.endObject();
                if (legacy && fields.contains("idTag") && id <= 0) continue;
                if (id <= 0 || type < 0 || type >= TagType.values.length || name == null
                    || (references && (!fields.contains("count") || (!legacy && count < 0)))
                    || (rules && (status < 0 || status > 2))
                    || (status > 0 && type == 0) || (!legacy && rules && status == 0)) {
                    throw new IOException("Invalid backup tag row");
                }
                if (!TagCatalog.isPortableLabel(name)) {
                    if (!legacy) throw new IOException("Invalid backup tag label");
                    name = "";
                }
                if (reference != null) insert(reference, id, name, type, Math.max(0, count));
                if (rules && status > 0) insert(rule, id, name, type, status);
            }
            reader.endArray();
        }
    }

    static void mergeReferences(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO Tags (idTag, name, type, count, provisional)"
            + " SELECT idTag, name, type, count, 1 FROM " + STAGE
            + " WHERE idTag IN (" + REFERENCED_IDS + ")");
        db.execSQL("DROP TABLE " + STAGE);
    }

    private static int readInt(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected backup tag integer");
        return Integer.parseInt(reader.nextString());
    }

    private static void insert(SQLiteStatement statement, int id, String name, int type, int value) {
        statement.bindLong(1, id);
        statement.bindString(2, name);
        statement.bindLong(3, type);
        statement.bindLong(4, value);
        statement.executeInsert();
    }
}
