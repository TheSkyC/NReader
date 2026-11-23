// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;

import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.settings.TagV2;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Portable transfer for local tag rules; catalog and account state stay out of the format. */
public final class TagRuleTransfer {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RULES = 10_000;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public enum Mode { MERGE, REPLACE }

    public static final class Preview {
        public final int total;
        public final int additions;
        public final int updates;
        public final int unchanged;
        public final int conflicts;

        private Preview(int total, int additions, int updates, int unchanged, int conflicts) {
            this.total = total;
            this.additions = additions;
            this.updates = updates;
            this.unchanged = unchanged;
            this.conflicts = conflicts;
        }
    }

    public static final class Result {
        public final int affected;
        public final int additions;
        public final int updates;
        public final int unchanged;

        private Result(int affected, int additions, int updates, int unchanged) {
            this.affected = affected;
            this.additions = additions;
            this.updates = updates;
            this.unchanged = unchanged;
        }
    }

    private static final class Rule {
        final int id;
        final int type;
        final int status;
        final String name;

        Rule(int id, int type, int status, String name) {
            this.id = id;
            this.type = type;
            this.status = status;
            this.name = name;
        }
    }

    private TagRuleTransfer() {}

    @NonNull
    public static String exportJson(@NonNull SQLiteDatabase database) throws IOException {
        StringBuilder output = new StringBuilder("{\"schemaVersion\":1,\"rules\":[");
        try (Cursor cursor = database.rawQuery("SELECT r.idTag, r.type, r.status,"
            + " COALESCE(t.name, r.name) AS name FROM TagRules r"
            + " LEFT JOIN Tags t ON t.idTag = r.idTag AND t.type = r.type"
            + " ORDER BY r.idTag", null)) {
            boolean first = true;
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) <= 0 || cursor.getInt(1) <= 0 || cursor.getInt(1) >= TagType.values.length
                    || cursor.getInt(2) < TagStatus.AVOIDED.ordinal()
                    || cursor.getInt(2) > TagStatus.ACCEPTED.ordinal()) {
                    throw new IOException("Invalid stored tag rule");
                }
                if (!first) output.append(',');
                first = false;
                output.append("{\"idTag\":").append(cursor.getInt(0))
                    .append(",\"type\":").append(cursor.getInt(1))
                    .append(",\"status\":").append(cursor.getInt(2))
                    .append(",\"name\":").append(JSONObject.quote(cursor.getString(3)))
                    .append('}');
            }
        }
        output.append("]}");
        if (output.length() > MAX_BYTES) throw new IOException("Tag rule export is too large");
        return output.toString();
    }

    @NonNull
    public static Preview preview(@NonNull SQLiteDatabase database, @NonNull InputStream input) throws IOException {
        List<Rule> rules = readRules(input);
        int additions = 0;
        int updates = 0;
        int unchanged = 0;
        int conflicts = 0;
        for (Rule rule : rules) {
            Existing existing = existing(database, rule.id);
            if (existing.conflictType(rule.type)) {
                conflicts++;
            } else if (!existing.present) {
                additions++;
            } else if (existing.status == rule.status) {
                unchanged++;
            } else {
                updates++;
            }
        }
        return new Preview(rules.size(), additions, updates, unchanged, conflicts);
    }

    @NonNull
    public static Result apply(@NonNull SQLiteDatabase database, @NonNull InputStream input,
                               @NonNull Mode mode) throws IOException {
        List<Rule> rules = readRules(input);
        for (Rule rule : rules) {
            if (existing(database, rule.id).conflictType(rule.type))
                throw new IOException("Tag rule identity conflicts with stored metadata");
        }
        long generation = Login.onlineTagsRestoreGeneration();
        if (Login.isRestoringTagData()) throw new IOException("Tag data restore is active");
        Result result;
        database.beginTransactionNonExclusive();
        try {
            if (Login.isRestoringTagData() || generation != Login.onlineTagsRestoreGeneration())
                throw new IOException("Tag data changed during rule import");
            if (mode == Mode.REPLACE) database.delete(Queries.TagTable.RULES_TABLE, null, null);
            int additions = 0;
            int updates = 0;
            int unchanged = 0;
            for (Rule rule : rules) {
                Existing before = existing(database, rule.id);
                if (before.present && before.type != rule.type)
                    throw new IOException("Tag rule identity conflicts with stored metadata");
                Tag tag = new Tag(rule.name, 0, rule.id, TagType.values[rule.type], TagStatus.values()[rule.status]);
                if (!Queries.TagTable.storeRule(tag, TagStatus.values()[rule.status]))
                    throw new IOException("Unable to store tag rule");
                if (!before.present) additions++;
                else if (before.status == rule.status) unchanged++;
                else updates++;
            }
            boolean countAvoided = Global.removeAvoidedGalleries();
            String where = countAvoided ? "status IN (1,2)" : "status = 2";
            try (Cursor count = database.rawQuery("SELECT COUNT(*) FROM TagRules WHERE " + where, null)) {
                if (count.moveToFirst() && count.getInt(0) > TagV2.MAXTAGS)
                    throw new IOException("Imported rules exceed the tag limit");
            }
            database.setTransactionSuccessful();
            result = new Result(additions + updates, additions, updates, unchanged);
        } finally {
            database.endTransaction();
        }
        TagRuleEdits.publishExternalChange();
        return result;
    }

    private static List<Rule> readRules(InputStream input) throws IOException {
        byte[] bytes = readBounded(input);
        List<Rule> rules = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(
            new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            reader.beginObject();
            boolean schemaSeen = false;
            boolean rulesSeen = false;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if ("schemaVersion".equals(field)) {
                    if (schemaSeen || readInt(reader) != SCHEMA_VERSION) throw new IOException("Unsupported tag rule schema");
                    schemaSeen = true;
                } else if ("rules".equals(field)) {
                    if (rulesSeen) throw new IOException("Duplicate tag rule list");
                    rulesSeen = true;
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (rules.size() >= MAX_RULES) throw new IOException("Too many tag rules");
                        Rule rule = readRule(reader);
                        if (!identities.add(rule.id + ":" + rule.type))
                            throw new IOException("Duplicate tag rule identity");
                        rules.add(rule);
                    }
                    reader.endArray();
                } else {
                    throw new IOException("Unknown tag rule field");
                }
            }
            reader.endObject();
            if (!schemaSeen || !rulesSeen || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Incomplete tag rule document");
        } catch (NumberFormatException | IllegalStateException error) {
            throw new IOException("Invalid tag rule document", error);
        }
        return rules;
    }

    private static Rule readRule(JsonReader reader) throws IOException {
        int id = -1;
        int type = -1;
        int status = -1;
        String name = null;
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw new IOException("Duplicate tag rule field");
            switch (field) {
                case "idTag": id = readInt(reader); break;
                case "type": type = readInt(reader); break;
                case "status": status = readInt(reader); break;
                case "name":
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid tag rule name");
                    name = reader.nextString();
                    break;
                default: throw new IOException("Unknown tag rule field");
            }
        }
        reader.endObject();
        if (id <= 0 || type <= 0 || type >= TagType.values.length
            || status < TagStatus.AVOIDED.ordinal() || status > TagStatus.ACCEPTED.ordinal()
            || name == null || !TagCatalog.isPortableLabel(name)) {
            throw new IOException("Invalid tag rule row");
        }
        return new Rule(id, type, status, name);
    }

    private static int readInt(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected tag rule integer");
        String value = reader.nextString();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid tag rule integer", error);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IOException("Tag rule document is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Existing existing(SQLiteDatabase database, int id) {
        try (Cursor row = database.rawQuery("SELECT type, status FROM TagRules WHERE idTag = ?",
            new String[]{Integer.toString(id)})) {
            if (!row.moveToFirst()) {
                try (Cursor catalog = database.rawQuery("SELECT type FROM Tags WHERE idTag = ?",
                    new String[]{Integer.toString(id)})) {
                    if (catalog.moveToFirst())
                        return new Existing(false, true, catalog.getInt(0), TagStatus.DEFAULT.ordinal());
                }
                return new Existing(false, false, -1, TagStatus.DEFAULT.ordinal());
            }
            return new Existing(true, true, row.getInt(0), row.getInt(1));
        }
    }

    private static final class Existing {
        final boolean present;
        final boolean known;
        final int type;
        final int status;

        Existing(boolean present, boolean known, int type, int status) {
            this.present = present;
            this.known = known;
            this.type = type;
            this.status = status;
        }

        boolean conflictType(int incomingType) {
            return known && type != incomingType;
        }
    }
}
