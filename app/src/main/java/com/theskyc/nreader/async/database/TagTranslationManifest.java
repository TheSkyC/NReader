package com.theskyc.nreader.async.database;

import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;

import com.theskyc.nreader.utility.JsonStringGuard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;

/** Integrity metadata for independently versioned locale packs. */
public final class TagTranslationManifest {
    public static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_LOCALES = 128;

    private final Map<String, Pack> packs;

    private TagTranslationManifest(Map<String, Pack> packs) {
        this.packs = Collections.unmodifiableMap(packs);
    }

    public static HttpUrl manifestUrl(String configured) throws IOException {
        return TagFeedFiles.manifestUrl(configured);
    }

    public Map<String, Pack> getPacks() {
        return packs;
    }

    public static TagTranslationManifest parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid translation manifest size");
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            throw new IOException("Translation manifest must not contain a byte order mark");
        }
        try (JsonReader reader = JsonStringGuard.open(new ByteArrayInputStream(bytes), 384)) {
            Set<String> fields = new HashSet<>();
            int schemaVersion = 0;
            String source = null;
            String kind = null;
            Map<String, Pack> packs = new LinkedHashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) throw new IOException("Duplicate translation manifest field");
                switch (name) {
                    case "schemaVersion": schemaVersion = TagFeedFiles.positiveInteger(reader); break;
                    case "source": source = TagFeedFiles.string(reader); break;
                    case "kind": kind = TagFeedFiles.string(reader); break;
                    case "packs":
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (packs.size() == MAX_LOCALES) throw new IOException("Too many translation locales");
                            Pack pack = readPack(reader);
                            if (packs.put(pack.locale, pack) != null) throw new IOException("Duplicate translation locale");
                        }
                        reader.endArray();
                        break;
                    default: throw new IOException("Unknown translation manifest field");
                }
            }
            reader.endObject();
            if (schemaVersion != 1 || !"nreader-translations".equals(source) || !"translations".equals(kind)
                || packs.isEmpty() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Unsupported tag translation manifest");
            }
            return new TagTranslationManifest(packs);
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Invalid tag translation manifest", error);
        }
    }

    private static Pack readPack(JsonReader reader) throws IOException {
        Set<String> fields = new HashSet<>();
        String locale = null;
        int revision = 0;
        int sizeBytes = 0;
        String sha256 = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!fields.add(name)) throw new IOException("Duplicate translation descriptor field");
            switch (name) {
                case "locale": locale = TagFeedFiles.string(reader); break;
                case "revision": revision = TagFeedFiles.positiveInteger(reader); break;
                case "sizeBytes": sizeBytes = TagFeedFiles.positiveInteger(reader); break;
                case "sha256": sha256 = TagFeedFiles.string(reader); break;
                default: throw new IOException("Unknown translation descriptor field");
            }
        }
        reader.endObject();
        if (!TagTranslationCatalog.isSupportedLocale(locale) || revision == 0 || sizeBytes == 0
            || sizeBytes > TagTranslationCatalog.MAX_PACK_BYTES || !TagFeedFiles.isSha256(sha256)) {
            throw new IOException("Invalid translation descriptor");
        }
        return new Pack(locale, revision, sizeBytes, sha256);
    }

    public static final class Pack {
        public final String locale;
        public final int revision;
        public final int sizeBytes;
        private final String sha256;

        private Pack(String locale, int revision, int sizeBytes, String sha256) {
            this.locale = locale;
            this.revision = revision;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        public HttpUrl payloadUrl(HttpUrl manifestUrl) throws IOException {
            return manifestUrl(manifestUrl.toString()).resolve("translation-" + sha256 + ".json");
        }

        /** The staged file must be app-private and unchanged during verification and import. */
        public boolean apply(SQLiteDatabase database, File staged, BooleanSupplier cancelled) throws IOException {
            if (revision <= TagTranslationCatalog.installedRevision(database, locale)) return false;
            try (FileInputStream input = new FileInputStream(staged)) {
                TagFeedFiles.verifyAndRewind(input, sizeBytes, sha256, cancelled);
                return TagTranslationCatalog.apply(database, input, locale, revision, cancelled);
            }
        }
    }
}
