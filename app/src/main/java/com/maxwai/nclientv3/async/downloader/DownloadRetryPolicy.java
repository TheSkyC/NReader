// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DownloadRetryPolicy {
    public static final int MAX_ATTEMPTS = 4;
    public static final long MAX_DELAY_MILLIS = 30000;

    private DownloadRetryPolicy() {}

    public static long delayMillis(int failedAttempts, String retryAfter, long now) {
        long delay = Math.min(MAX_DELAY_MILLIS, 1000L << Math.min(5, Math.max(0, failedAttempts - 1)));
        if (retryAfter == null) return delay;
        try {
            double seconds = Double.parseDouble(retryAfter.trim());
            if (Double.isFinite(seconds) && seconds > 0) {
                if (seconds * 1000 > MAX_DELAY_MILLIS) return -1;
                delay = Math.max(delay, (long) (seconds * 1000));
            }
        } catch (NumberFormatException exception) {
            try {
                long date = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                if (date - now > MAX_DELAY_MILLIS) return -1;
                delay = Math.max(delay, Math.min(MAX_DELAY_MILLIS, Math.max(0, date - now)));
            } catch (DateTimeParseException ignored) {
                // Invalid server hints use the bounded default backoff.
            }
        }
        return delay;
    }
}
