package com.theskyc.nreader.components.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImageCacheQuotaTest {
    @Test
    public void supportedValuesUseMebibytes() {
        assertEquals(250L * 1024 * 1024, ImageCacheQuota.bytesForValue("250"));
        assertEquals(512L * 1024 * 1024, ImageCacheQuota.bytesForValue("512"));
        assertEquals(1024L * 1024 * 1024, ImageCacheQuota.bytesForValue("1024"));
    }

    @Test
    public void invalidValuesFallBackToCurrentDefault() {
        long defaultBytes = 250L * 1024 * 1024;
        assertEquals(defaultBytes, ImageCacheQuota.bytesForValue(null));
        assertEquals(defaultBytes, ImageCacheQuota.bytesForValue("0"));
        assertEquals(defaultBytes, ImageCacheQuota.bytesForValue("unlimited"));
    }
}
