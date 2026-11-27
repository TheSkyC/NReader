// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;

final class CacheDirectorySizer {
    private CacheDirectorySizer() {
    }

    static long directorySize(@NonNull File root) throws IOException {
        if (!root.exists() || Files.isSymbolicLink(root.toPath())) return 0;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        long size = 0;
        while (!pending.isEmpty()) {
            File current = pending.removeLast();
            if (Files.isSymbolicLink(current.toPath())) continue;
            if (current.isFile()) {
                long fileSize = current.length();
                if (fileSize > Long.MAX_VALUE - size) return Long.MAX_VALUE;
                size += fileSize;
                continue;
            }
            if (!current.isDirectory()) continue;
            File[] children = current.listFiles();
            if (children == null) throw new IOException("Unable to inspect cache directory");
            for (File child : children) pending.add(child);
        }
        return size;
    }
}
