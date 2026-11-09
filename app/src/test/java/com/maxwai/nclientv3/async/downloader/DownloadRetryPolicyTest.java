// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.downloader;

import org.junit.Test;

import static org.junit.Assert.*;

public class DownloadRetryPolicyTest {
    @Test
    public void classifiesPermanentAndTransientFailures() {
        assertEquals(DownloadFailure.AUTHENTICATION, DownloadFailure.fromHttpCode(403));
        assertFalse(DownloadFailure.fromHttpCode(401).retryable);
        assertEquals(DownloadFailure.NOT_FOUND, DownloadFailure.fromHttpCode(404));
        assertFalse(DownloadFailure.STORAGE.retryable);
        assertTrue(DownloadFailure.fromHttpCode(429).retryable);
        assertTrue(DownloadFailure.fromHttpCode(503).retryable);
    }

    @Test
    public void backoffIsExponentialAndBounded() {
        assertEquals(1000, DownloadRetryPolicy.delayMillis(1, null, 0));
        assertEquals(2000, DownloadRetryPolicy.delayMillis(2, null, 0));
        assertEquals(4000, DownloadRetryPolicy.delayMillis(3, null, 0));
        assertEquals(30000, DownloadRetryPolicy.delayMillis(100, null, 0));
        assertEquals(4, DownloadRetryPolicy.MAX_ATTEMPTS);
    }

    @Test
    public void handlesSecondsDatesAndMalformedRetryHints() {
        assertEquals(10000, DownloadRetryPolicy.delayMillis(1, "10", 0));
        assertEquals(1000, DownloadRetryPolicy.delayMillis(1, "nonsense", 0));
        assertEquals(1000, DownloadRetryPolicy.delayMillis(1, "-5", 0));
        assertEquals(-1, DownloadRetryPolicy.delayMillis(1, "99999999999999999999999", 0));
        assertEquals(10000, DownloadRetryPolicy.delayMillis(1, "Thu, 1 Jan 1970 00:00:10 GMT", 0));
    }
}
