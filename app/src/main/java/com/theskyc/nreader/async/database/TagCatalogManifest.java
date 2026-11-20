package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.utility.JsonStringGuard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;

/** Integrity metadata for a full catalog from the configured feed. */
public final class TagCatalogManifest {
    public static final int MAX_BYTES = 4096;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    static final String SOURCE = "nreader-catalog";

    public final int revision;
    public final int sizeBytes;
    private final String sha256;

    private TagCatalogManifest(int revision, int sizeBytes, String sha256) {
        this.revision = revision;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }

    public static HttpUrl manifestUrl(String configured) throws IOException {
        HttpUrl url = HttpUrl.parse(configured);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
            || url.query() != null || url.fragment() != null
            || !url.encodedPath().endsWith("/manifest.json")) {
            throw new IOException("Invalid configured tag catalog manifest URL");
        }
        return url;
    }

    public HttpUrl payloadUrl(HttpUrl manifestUrl) throws IOException {
        return manifestUrl(manifestUrl.toString()).resolve("catalog-" + sha256 + ".json");
    }

    public static TagCatalogManifest parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid manifest size");
        try (JsonReader reader = JsonStringGuard.open(new ByteArrayInputStream(bytes), 384)) {
            Set<String> fields = new HashSet<>();
            int schemaVersion = 0;
            int revision = 0;
            int sizeBytes = 0;
            String source = null;
            String kind = null;
            String sha256 = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) throw new IOException("Duplicate manifest field");
                switch (name) {
                    case "schemaVersion": schemaVersion = positiveInteger(reader); break;
                    case "revision": revision = positiveInteger(reader); break;
                    case "sizeBytes": sizeBytes = positiveInteger(reader); break;
                    case "source": source = string(reader); break;
                    case "kind": kind = string(reader); break;
                    case "sha256": sha256 = string(reader); break;
                    default: throw new IOException("Unknown manifest field");
                }
            }
            reader.endObject();
            if (schemaVersion != 1 || revision == 0 || sizeBytes == 0 || sizeBytes > MAX_PAYLOAD_BYTES
                || !SOURCE.equals(source) || !"catalog".equals(kind) || sha256 == null
                || !sha256.matches("[0-9a-f]{64}") || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Unsupported tag catalog manifest");
            }
            return new TagCatalogManifest(revision, sizeBytes, sha256);
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Invalid tag catalog manifest", error);
        }
    }

    public boolean apply(SQLiteDatabase database, File staged, BooleanSupplier cancelled) throws IOException {
        return apply(database, staged, TagCatalog.MIN_DIRECTORY_ROWS, cancelled);
    }

    boolean apply(SQLiteDatabase database, File staged, int minimumRows, BooleanSupplier cancelled) throws IOException {
        if (revision <= TagCatalog.installedPublishedRevision(database)) return false;
        try (FileInputStream input = new FileInputStream(staged)) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                TagCatalog.checkCancelled(cancelled);
                total += count;
                if (total > sizeBytes) throw new IOException("Catalog payload exceeds declared size");
                digest.update(buffer, 0, count);
            }
            if (total != sizeBytes || !MessageDigest.isEqual(digest.digest(), decodeDigest(sha256))) {
                throw new IOException("Catalog payload does not match manifest");
            }
            input.getChannel().position(0);
            return TagCatalog.applyPublished(database, input, revision, minimumRows, cancelled);
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected manifest string");
        return reader.nextString();
    }

    private static int positiveInteger(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected manifest integer");
        int value = Integer.parseInt(reader.nextString());
        if (value <= 0) throw new IOException("Manifest integer out of range");
        return value;
    }

    private static byte[] decodeDigest(String value) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
