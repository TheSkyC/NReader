// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class VersionCheckerTest {
    @Test
    public void comparesPreReleaseStagesAndStableVersions() {
        assertTrue(isNewer("1.0.0-alpha.1", "1.0.0-pre1"));
        assertTrue(isNewer("1.0.0-beta.1", "1.0.0-alpha.2"));
        assertTrue(isNewer("1.0.0-rc.1", "1.0.0-beta.9"));
        assertTrue(isNewer("1.0.0", "1.0.0-rc.1"));
        assertFalse(isNewer("1.0.0-beta.1", "1.0.0"));
    }

    @Test
    public void acceptsLegacyAndPrefixedReleaseTags() {
        assertTrue(isNewer("v1.0.0-beta.1", "1.0.0-pre1"));
        assertFalse(isNewer("1.0.0-pre1", "1.0.0-pre1"));
        assertFalse(isNewer("1.0.0-beta.1", "1.0.0-beta.2-debug"));
    }

    @Test
    public void selectsNewestCompatibleReleaseInsteadOfFirstArrayEntry() {
        VersionChecker.GitHubRelease oldRelease = release("1.0.0", "https://example.invalid/old.apk");
        VersionChecker.GitHubRelease newRelease = release("1.1.0", "https://example.invalid/new.apk");
        VersionChecker.GitHubRelease release = VersionChecker.selectNewestCompatible(
            Arrays.asList(oldRelease, newRelease));

        assertNotNull(release);
        assertEquals("1.1.0", release.versionCode);
        assertEquals("https://example.invalid/new.apk", release.downloadUrl);
    }

    @Test
    public void selectsAssetForRequestedFlavorOnly() {
        assertTrue(VersionChecker.matchesFlavorAsset(
            "NReader-1.1.0-minApi26-release.apk", "minApi26"));
        assertFalse(VersionChecker.matchesFlavorAsset(
            "NReader-1.1.0-minApi28-release.apk", "minApi26"));
    }

    @Test
    public void ignoresReleaseWithoutCompatibleApk() {
        VersionChecker.GitHubRelease release = VersionChecker.selectNewestCompatible(
            Arrays.asList(release("1.1.0", null)));

        assertNull(release);
    }

    private static VersionChecker.GitHubRelease release(String version, String url) {
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = version;
        release.downloadUrl = url;
        return release;
    }

    private static boolean isNewer(String candidate, String current) {
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = candidate;
        return release.isNewerThenVersion(current);
    }
}
