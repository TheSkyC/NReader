// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.converters;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class ExportFile implements Closeable {
    private final File output;
    private final File temporary;
    private boolean published;

    private ExportFile(File output, File temporary) {
        this.output = output;
        this.temporary = temporary;
    }

    static ExportFile create(@NonNull File directory, @NonNull String title, int galleryId,
                             @NonNull String extension) throws IOException {
        if (!extension.matches("\\.[A-Za-z0-9]{1,8}")) throw new IOException("Invalid export extension");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create export directory");
        File canonicalDirectory = directory.getCanonicalFile();
        String baseName = safeBaseName(title, galleryId);
        File output = null;
        for (int suffix = 1; suffix < 10000; suffix++) {
            String suffixText = suffix == 1 ? "" : " (" + suffix + ")";
            File candidate = new File(canonicalDirectory, baseName + suffixText + extension);
            if (!canonicalDirectory.equals(candidate.getCanonicalFile().getParentFile())) {
                throw new IOException("Export path escaped its directory");
            }
            if (candidate.createNewFile()) {
                output = candidate;
                break;
            }
        }
        if (output == null) throw new IOException("Unable to reserve export file name");
        try {
            return new ExportFile(output, File.createTempFile("nreader-export-", ".part", canonicalDirectory));
        } catch (IOException exception) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw exception;
        }
    }

    File getOutputFile() {
        return output;
    }

    File getTemporaryFile() {
        return temporary;
    }

    void publish() throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(temporary, true)) {
            outputStream.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        published = true;
    }

    @Override
    public void close() {
        if (temporary.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
        if (!published && output.exists()) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
    }

    static String safeBaseName(String title, int galleryId) {
        String value = title == null ? "" : title;
        StringBuilder safe = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || isUnsafeFilenameChar(codePoint)) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
            if (safe.codePointCount(0, safe.length()) >= 120) break;
        }
        String result = safe.toString().trim();
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() || ".".equals(result) || "..".equals(result)
            ? "gallery-" + Math.max(0, galleryId) : result;
    }

    private static boolean isUnsafeFilenameChar(int codePoint) {
        return codePoint == '/' || codePoint == '\\' || codePoint == ':' || codePoint == '*'
            || codePoint == '?' || codePoint == '"' || codePoint == '<' || codePoint == '>'
            || codePoint == '|';
    }
}
