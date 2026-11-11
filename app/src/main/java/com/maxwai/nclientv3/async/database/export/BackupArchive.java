// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database.export;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class BackupArchive {
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_NAME_LENGTH = 1024;
    private static final int MAX_DATABASE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PREFERENCES_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_UNKNOWN_BYTES = 16 * 1024 * 1024;

    byte[] database;
    byte[] manifest;
    interface PreferencesConsumer {
        void accept(String name, InputStream source) throws IOException;
    }

    static BackupArchive read(InputStream source, PreferencesConsumer preferences) throws IOException {
        if (source == null) throw new IOException("Unable to open backup archive");
        BackupArchive archive = new BackupArchive();
        Set<String> names = new HashSet<>();
        int unknownBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (names.size() >= MAX_ENTRIES || name.length() > MAX_NAME_LENGTH)
                    throw new IOException("Too many or oversized backup entry names");
                if (!names.add(name)) throw new IOException("Duplicate backup entry");
                switch (name) {
                    case Exporter.DB_ZIP_FILE: archive.database = readEntry(zip, MAX_DATABASE_BYTES); break;
                    case Exporter.MANIFEST_ZIP_FILE: archive.manifest = readEntry(zip, MAX_MANIFEST_BYTES); break;
                    case "Settings.json":
                    case "ScrapedTags.json":
                        InputStream bounded = new BoundedInputStream(zip, MAX_PREFERENCES_BYTES);
                        if (preferences != null) preferences.accept(name, bounded);
                        drain(bounded, MAX_PREFERENCES_BYTES);
                        break;
                    default: unknownBytes += drain(zip, MAX_UNKNOWN_BYTES - unknownBytes); break;
                }
                zip.closeEntry();
            }
        }
        if (archive.database == null) throw new IOException("Backup has no database");
        if (archive.manifest != null) Importer.validateManifest(archive.manifest, archive.database);
        return archive;
    }

    private static byte[] readEntry(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Backup entry exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static int drain(InputStream source, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (count > limit - total) throw new IOException("Unknown backup entries exceed size limit");
            total += count;
        }
        return total;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final int limit;
        private int total;

        BoundedInputStream(InputStream source, int limit) {
            super(source);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++total > limit) throw new IOException("Backup entry exceeds size limit");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, Math.min(length, limit - total + 1));
            if (count > limit - total) throw new IOException("Backup entry exceeds size limit");
            if (count > 0) total += count;
            return count;
        }
    }
}
