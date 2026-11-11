package com.maxwai.nclientv3.loginapi;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class PowSolver {
    private PowSolver() {
    }

    @NonNull
    public static String solve(@NonNull String challenge, int difficulty, long timeoutMillis,
                               @NonNull BooleanSupplier cancelled) {
        if (challenge.isEmpty() || challenge.length() > 4096 || difficulty < 0 || difficulty > 256
            || timeoutMillis <= 0) {
            throw new IllegalArgumentException("difficulty out of range");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] challengeBytes = challenge.getBytes(StandardCharsets.UTF_8);
            long nonce = 0L;
            long started = System.nanoTime();
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                if ((nonce & 1023L) == 0 && (Thread.currentThread().isInterrupted()
                    || cancelled.getAsBoolean() || System.nanoTime() - started >= timeoutNanos)) {
                    throw new CancellationException("PoW cancelled or timed out");
                }
                String nonceText = Long.toString(nonce);
                digest.reset();
                digest.update(challengeBytes);
                digest.update(nonceText.getBytes(StandardCharsets.US_ASCII));
                if (hasLeadingZeroBits(digest.digest(), difficulty)) return nonceText;
                if (nonce == Long.MAX_VALUE) throw new CancellationException("PoW nonce space exhausted");
                nonce++;
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static boolean hasLeadingZeroBits(@NonNull byte[] hash, int bits) {
        if (bits < 0 || bits > hash.length * 8) throw new IllegalArgumentException("Invalid bit count");
        int fullBytes = bits >>> 3;
        for (int i = 0; i < fullBytes; i++) if (hash[i] != 0) return false;
        int remaining = bits & 7;
        return remaining == 0 || ((hash[fullBytes] & 0xff) >>> (8 - remaining)) == 0;
    }
}
