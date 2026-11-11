// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;

final class BackupJson {
    private static final int MAX_NESTING = 64;

    private BackupJson() {}

    static void skipValue(JsonReader reader) throws IOException {
        skipValue(reader, 0);
    }

    static void skipValue(JsonReader reader, int parentDepth) throws IOException {
        int depth = 0;
        do {
            JsonToken token = reader.peek();
            switch (token) {
                case BEGIN_ARRAY:
                case BEGIN_OBJECT:
                    if (++depth + parentDepth > MAX_NESTING) throw new IOException("Backup JSON nesting exceeds limit");
                    if (token == JsonToken.BEGIN_ARRAY) reader.beginArray();
                    else reader.beginObject();
                    break;
                case END_ARRAY:
                    reader.endArray();
                    depth--;
                    break;
                case END_OBJECT:
                    reader.endObject();
                    depth--;
                    break;
                case NAME:
                    reader.nextName();
                    break;
                case STRING:
                case NUMBER:
                case BOOLEAN:
                case NULL:
                    reader.skipValue();
                    break;
                default:
                    throw new IOException("Invalid backup JSON value");
            }
        } while (depth > 0);
    }
}
