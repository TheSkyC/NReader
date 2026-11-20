// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
            + "\"schemaVersion\":1,\"versionName\":\"1.1.0\",\"versionCode\":11000,\"channel\":\"stable\","
            + "\"assets\":["
            + "{\"id\":\"minApi26\",\"flavor\":\"minApi26\",\"packageName\":\"com.theskyc.nreader\",\"minSdk\":26,\"sizeBytes\":120,\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"url\":\"https://example.invalid/26.apk\"},"
            + "{\"id\":\"minApi28\",\"flavor\":\"minApi28\",\"packageName\":\"com.theskyc.nreader\",\"minSdk\":28,\"sizeBytes\":120,\"sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"url\":\"https://example.invalid/28.apk\"}"
            + "]}";

        VersionChecker.GitHubRelease release = VersionChecker.parseManifestJson(
            new JsonReader(new StringReader(json)), false, "minApi28", "com.theskyc.nreader");

        assertNotNull(release);
        assertEquals("1.1.0", release.getVersionCode());
        assertEquals("https://example.invalid/28.apk", release.getDownloadUrl());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", release.getSha256());
    }

    @Test
    public void rejectsManifestWithInvalidDigest() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionName\":\"1.1.0\",\"versionCode\":11000,\"channel\":\"stable\","
            + "\"assets\":[{\"id\":\"minApi28\",\"flavor\":\"minApi28\",\"packageName\":\"com.theskyc.nreader\","
            + "\"minSdk\":28,\"sizeBytes\":120,\"sha256\":\"bad\",\"url\":\"https://example.invalid/28.apk\"}]}";

        assertNull(VersionChecker.parseManifestJson(
            new JsonReader(new StringReader(json)), false, "minApi28", "com.theskyc.nreader"));
    }

    @Test
    public void rejectsManifestWithOversizedAsset() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionName\":\"1.1.0\",\"versionCode\":11000,\"channel\":\"stable\","
            + "\"assets\":[{\"id\":\"minApi28\",\"flavor\":\"minApi28\",\"packageName\":\"com.theskyc.nreader\","
            + "\"minSdk\":28,\"sizeBytes\":" + (VersionChecker.MAX_UPDATE_SIZE_BYTES + 1L) + ","
            + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"url\":\"https://example.invalid/28.apk\"}]}";

        assertNull(VersionChecker.parseManifestJson(
            new JsonReader(new StringReader(json)), false, "minApi28", "com.theskyc.nreader"));
    }
}
