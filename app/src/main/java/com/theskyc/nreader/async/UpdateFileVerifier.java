// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class UpdateFileVerifier {
    private UpdateFileVerifier() {}

    static boolean matchesSha256(@NonNull File file, @Nullable String expected) throws IOException {
        if (expected == null || expected.trim().isEmpty()) return true;
        String normalized = expected.startsWith("sha256:") ? expected.substring(7) : expected;
        return normalized.equalsIgnoreCase(sha256(file));
    }

    @NonNull
    static String sha256(@NonNull File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required on Android", exception);
        }
    }
}
