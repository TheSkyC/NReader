// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    }

    private static boolean isNewer(String candidate, String current) {
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = candidate;
        return release.isNewerThenVersion(current);
    }
}
