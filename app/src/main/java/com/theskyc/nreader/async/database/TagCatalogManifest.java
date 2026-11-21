package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.utility.JsonStringGuard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
        return TagFeedFiles.manifestUrl(configured);
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
                    case "schemaVersion": schemaVersion = TagFeedFiles.positiveInteger(reader); break;
                    case "revision": revision = TagFeedFiles.positiveInteger(reader); break;
                    case "sizeBytes": sizeBytes = TagFeedFiles.positiveInteger(reader); break;
                    case "source": source = TagFeedFiles.string(reader); break;
                    case "kind": kind = TagFeedFiles.string(reader); break;
                    case "sha256": sha256 = TagFeedFiles.string(reader); break;
                    default: throw new IOException("Unknown manifest field");
                }
            }
            reader.endObject();
            if (schemaVersion != 1 || revision == 0 || sizeBytes == 0 || sizeBytes > MAX_PAYLOAD_BYTES
                || !SOURCE.equals(source) || !"catalog".equals(kind)
                || !TagFeedFiles.isSha256(sha256) || reader.peek() != JsonToken.END_DOCUMENT) {
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
            TagFeedFiles.verifyAndRewind(input, sizeBytes, sha256, cancelled);
            return TagCatalog.applyPublished(database, input, revision, minimumRows, cancelled);
        }
    }
}
