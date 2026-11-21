package com.theskyc.nreader.async.database;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;

final class TagFeedFiles {
    private TagFeedFiles() {}

    static HttpUrl manifestUrl(String configured) throws IOException {
        HttpUrl url = HttpUrl.parse(configured);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
            || url.query() != null || url.fragment() != null
            || !url.encodedPath().endsWith("/manifest.json")) {
            throw new IOException("Invalid configured tag feed manifest URL");
        }
        return url;
    }

    static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected manifest string");
        return reader.nextString();
    }

    static int positiveInteger(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Expected manifest integer");
        int value = Integer.parseInt(reader.nextString());
        if (value <= 0) throw new IOException("Manifest integer out of range");
        return value;
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static void verifyAndRewind(FileInputStream input, int sizeBytes, String sha256,
                                BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        do {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw new InterruptedIOException("Tag feed verification cancelled");
            }
            count = input.read(buffer);
            if (count == -1) break;
            total += count;
            if (total > sizeBytes) throw new IOException("Tag feed payload exceeds declared size");
            digest.update(buffer, 0, count);
        } while (true);
        if (total != sizeBytes || !MessageDigest.isEqual(digest.digest(), decodeDigest(sha256))) {
            throw new IOException("Tag feed payload does not match manifest");
        }
        input.getChannel().position(0);
    }

    private static byte[] decodeDigest(String value) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
