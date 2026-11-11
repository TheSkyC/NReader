// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BackupPreview {
    private BackupPreview() {}

    public static Summary inspect(Context context, Uri file) throws IOException {
        BackupArchive archive = BackupArchive.read(context.getContentResolver().openInputStream(file), null);
        return new Summary(countRows(archive.database), archive.manifest != null);
    }

    private static Map<String, Integer> countRows(byte[] database) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(database), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String table = reader.nextName();
                int count = 0;
                reader.beginArray();
                while (reader.hasNext()) { BackupJson.skipValue(reader, 1); count++; }
                reader.endArray();
                counts.put(table, count);
            }
            reader.endObject();
            if (reader.peek() != android.util.JsonToken.END_DOCUMENT) throw new IOException("Trailing database backup data");
        }
        return counts;
    }

    static void validateDatabaseBytes(byte[] database) throws IOException {
        countRows(database);
    }

    public static final class Summary {
        public final Map<String, Integer> rowCounts;
        public final boolean integrityChecked;

        Summary(Map<String, Integer> rowCounts, boolean integrityChecked) {
            this.rowCounts = rowCounts;
            this.integrityChecked = integrityChecked;
        }

        public int totalRows() {
            int total = 0;
            for (Integer count : rowCounts.values()) total += count;
            return total;
        }
    }
}
