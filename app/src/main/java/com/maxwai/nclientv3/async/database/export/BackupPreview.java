// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class BackupPreview {
    private BackupPreview() {}

    public static Summary inspect(Context context, Uri file) throws IOException {
        byte[] database = null;
        byte[] manifest = null;
        try (InputStream source = context.getContentResolver().openInputStream(file);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (Exporter.DB_ZIP_FILE.equals(entry.getName())) database = read(zip, 64 * 1024 * 1024);
                else if (Exporter.MANIFEST_ZIP_FILE.equals(entry.getName())) manifest = read(zip, 64 * 1024);
                zip.closeEntry();
            }
        }
        if (database == null) throw new IOException("Backup has no database");
        if (manifest != null) Importer.validateManifest(manifest, database);
        return new Summary(countRows(database), manifest != null);
    }

    private static byte[] read(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Backup entry exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Map<String, Integer> countRows(byte[] database) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(database), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String table = reader.nextName();
                int count = 0;
                reader.beginArray();
                while (reader.hasNext()) { reader.skipValue(); count++; }
                reader.endArray();
                counts.put(table, count);
            }
            reader.endObject();
            if (reader.peek() != android.util.JsonToken.END_DOCUMENT) throw new IOException("Trailing database backup data");
        }
        return counts;
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
