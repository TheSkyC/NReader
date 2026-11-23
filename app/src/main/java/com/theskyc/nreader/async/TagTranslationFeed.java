package com.theskyc.nreader.async;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.utility.LogUtility;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.HttpUrl;

/** Fetches a public locale list and installs only the pack selected by the caller. */
public final class TagTranslationFeed {
    private static final Set<File> CLEANED_DIRECTORIES = new HashSet<>();

    private final Supplier<File> cacheDirectory;
    private final HttpUrl manifestUrl;
    private final TagFeedDownload download;
    private volatile boolean cancelled;

    public TagTranslationFeed(Context context, String manifestUrl) throws IOException {
        this(context::getCacheDir, manifestUrl, null);
    }

    TagTranslationFeed(File cacheDirectory, String manifestUrl, Call.Factory client) throws IOException {
        this(() -> cacheDirectory, manifestUrl, client);
    }

    private TagTranslationFeed(Supplier<File> cacheDirectory, String manifestUrl, Call.Factory client) throws IOException {
        this.cacheDirectory = cacheDirectory;
        this.manifestUrl = TagTranslationManifest.manifestUrl(manifestUrl);
        this.download = client == null ? new TagFeedDownload(() -> cancelled) : new TagFeedDownload(client, () -> cancelled);
    }

    public synchronized TagTranslationManifest readManifest() throws IOException {
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        download.copy(manifestUrl, staged, TagTranslationManifest.MAX_BYTES, true);
        return TagTranslationManifest.parse(staged.toByteArray());
    }

    public synchronized boolean install(SQLiteDatabase database, TagTranslationManifest.Pack pack) throws IOException {
        download.checkCancelled();
        long generation = TagTranslationCatalog.generationSnapshot(database);
        if (pack.revision <= TagTranslationCatalog.installedRevision(database, pack.locale)) return false;
        File file = createStagingFile(new File(cacheDirectory.get(), "tag-translation-feed"));
        try {
            try (OutputStream output = new FileOutputStream(file)) {
                download.copy(pack.payloadUrl(manifestUrl), output, pack.sizeBytes, true);
            }
            return pack.apply(database, file, generation, () -> cancelled);
        } finally {
            deleteStaged(file);
        }
    }

    public void cancel() {
        cancelled = true;
        download.cancelActiveCall();
    }

    private static synchronized File createStagingFile(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create translation feed staging directory");
        }
        if (!CLEANED_DIRECTORIES.contains(directory)) {
            // No import in this process has created a file here yet.
            File[] abandoned = directory.listFiles((parent, name) -> name.endsWith(".part"));
            if (abandoned == null) throw new IOException("Could not read translation feed staging directory");
            for (File file : abandoned) deleteStaged(file);
            CLEANED_DIRECTORIES.add(directory);
        }
        return File.createTempFile("translation-", ".part", directory);
    }

    private static void deleteStaged(File file) {
        if (!file.delete()) LogUtility.w("Could not remove staged tag translation");
    }
}
