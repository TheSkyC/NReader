// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Response;
import okhttp3.ResponseBody;

/** Receives and verifies one update artifact without owning its lifecycle. */
final class UpdateDownloadTransfer {
    private static final long PROGRESS_INTERVAL_MS = 500L;

    interface Progress {
        boolean report(long written, long total);
    }

    private UpdateDownloadTransfer() {}

    static boolean receive(@NonNull Response response, @NonNull File partial, long expectedSize,
                           @Nullable String sha256, @NonNull Progress progress) throws IOException {
        if (!VersionChecker.isValidSha256(sha256)) {
            throw new IOException("Update package checksum is missing or invalid");
        }
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) {
            throw new IOException("Unable to download update: HTTP " + response.code());
        }
        long contentLength = body.contentLength();
        if (contentLength > VersionChecker.MAX_UPDATE_SIZE_BYTES || expectedSize > VersionChecker.MAX_UPDATE_SIZE_BYTES) {
            throw new IOException("Update package exceeds the maximum allowed size");
        }
        if (contentLength >= 0L && expectedSize > 0L && contentLength != expectedSize) {
            throw new IOException("Response length does not match release metadata");
        }
        long total = expectedSize > 0L ? expectedSize : contentLength;
        long written = 0L;
        long lastProgress = SystemClock.elapsedRealtime();
        try (FileOutputStream output = new FileOutputStream(partial); InputStream input = body.byteStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (written + count > VersionChecker.MAX_UPDATE_SIZE_BYTES) {
                    throw new IOException("Update package exceeds the maximum allowed size");
                }
                if (expectedSize > 0L && written + count > expectedSize) {
                    throw new IOException("Update package exceeds the expected size");
                }
                output.write(buffer, 0, count);
                written += count;
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                    if (!progress.report(written, total)) return false;
                    lastProgress = now;
                }
            }
            output.flush();
        }
        if (contentLength >= 0L && written != contentLength) {
            throw new IOException("Downloaded update length does not match response");
        }
        if (expectedSize > 0L && written != expectedSize) {
            throw new IOException("Downloaded update size does not match release metadata");
        }
        if (!UpdateFileVerifier.matchesSha256(partial, sha256)) {
            throw new IOException("Downloaded update checksum does not match release metadata");
        }
        return true;
    }
}
