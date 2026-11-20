// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import android.util.JsonReader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.StringReader;

@RunWith(RobolectricTestRunner.class)
public class ReleaseListParsingTest {
    @Test
    public void emptyArrayIsAValidListWithoutCandidates() throws Exception {
        assertNull(parse("[]"));
    }

    @Test
    public void rejectsMalformedOrIncompleteReleaseLists() {
        for (String json : new String[]{"{}", "[{}", "[{}, 1]", "[] {}", "[true]"}) {
            assertThrows("Invalid release list must fail: " + json, IOException.class, () -> parse(json));
        }
    }

    @Test
    public void rejectsPartialListEvenAfterAValidCandidate() throws Exception {
        String candidate = "{\"tag_name\":\"1.2.0\",\"assets\":[{"
            + "\"name\":\"NReader-1.2.0-minApi28-release.apk\","
            + "\"browser_download_url\":\"https://example.invalid/update.apk\","
            + "\"digest\":\"0000000000000000000000000000000000000000000000000000000000000000\"}]}";
        assertNotNull(parse("[" + candidate + "]"));
        assertThrows(IOException.class, () -> parse("[" + candidate + ", 1]"));
    }

    @Test
    public void acceptsNullNotesAndSkipsAssetWithoutDigest() throws Exception {
        String json = "[{\"tag_name\":\"1.2.0\",\"body\":null,\"assets\":["
            + "{\"name\":\"NReader-1.2.0-minApi28-old.apk\","
            + "\"browser_download_url\":\"https://example.invalid/old.apk\",\"digest\":null},"
            + "{\"name\":\"NReader-1.2.0-minApi28-release.apk\","
            + "\"browser_download_url\":\"https://example.invalid/update.apk\","
            + "\"digest\":\"0000000000000000000000000000000000000000000000000000000000000000\"}]}]";
        VersionChecker.GitHubRelease release = parse(json);
        assertNotNull(release);
        assertNull(release.body);
        org.junit.Assert.assertEquals("https://example.invalid/update.apk", release.downloadUrl);
    }

    private static VersionChecker.GitHubRelease parse(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            return VersionChecker.parseVersionJson(reader, false, "minApi28");
        }
    }
}
