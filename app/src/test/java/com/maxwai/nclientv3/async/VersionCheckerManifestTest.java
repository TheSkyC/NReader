// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.util.JsonReader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.StringReader;

@RunWith(RobolectricTestRunner.class)
public class VersionCheckerManifestTest {
    @Test
    public void selectsManifestAssetForCurrentFlavorAndPackage() throws Exception {
        String json = "{"
            + "\"schemaVersion\":1,\"versionName\":\"1.1.0\",\"channel\":\"stable\","
            + "\"assets\":["
            + "{\"flavor\":\"minApi26\",\"packageName\":\"com.theskyc.nreader\",\"minSdk\":26,\"sha256\":\"abc\",\"url\":\"https://example.invalid/26.apk\"},"
            + "{\"flavor\":\"minApi28\",\"packageName\":\"com.theskyc.nreader\",\"minSdk\":28,\"sha256\":\"def\",\"url\":\"https://example.invalid/28.apk\"}"
            + "]}";

        VersionChecker.GitHubRelease release = VersionChecker.parseManifestJson(
            new JsonReader(new StringReader(json)), false, "minApi28", "com.theskyc.nreader");

        assertNotNull(release);
        assertEquals("1.1.0", release.getVersionCode());
        assertEquals("https://example.invalid/28.apk", release.getDownloadUrl());
        assertEquals("def", release.getSha256());
    }
}
