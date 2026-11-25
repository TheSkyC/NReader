// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class UpdateFeedTest {
    private static String manifest(String name, int code, String channel) {
        return "{\"schemaVersion\":1,\"versionName\":\"" + name + "\",\"versionCode\":" + code
            + ",\"channel\":\"" + channel + "\",\"assets\":[{\"id\":\"minApi28\",\"flavor\":\"minApi28\","
            + "\"packageName\":\"test.package\",\"minSdk\":28,\"sizeBytes\":100,"
            + "\"url\":\"https://example.invalid/a.apk\",\"sha256\":\""
            + "0000000000000000000000000000000000000000000000000000000000000000\"}]}";
    }

    private static VersionChecker.GitHubRelease parse(String json, boolean beta) throws IOException {
        return UpdateFeed.parse(json, beta, "minApi28", "test.package");
    }

    @Test public void selectsNewestAllowedChannel() throws Exception {
        String feed = "{\"schemaVersion\":1,\"stable\":" + manifest("1.0.0", 100, "stable")
            + ",\"beta\":" + manifest("1.1.0-beta.1", 110, "beta") + "}";
        assertEquals("1.0.0", parse(feed, false).versionCode);
        assertEquals("1.1.0-beta.1", parse(feed, true).versionCode);
    }

    @Test public void emptyStableChannelDoesNotOfferPrereleaseWithoutOptIn() throws Exception {
        String feed = "{\"schemaVersion\":1,\"stable\":null,\"beta\":" + manifest("1.1.0-beta.1", 110, "beta") + "}";
        assertNull(parse(feed, false));
    }

    @Test public void rejectsIncompleteAndInvalidFeeds() {
        assertThrows(IOException.class, () -> parse("{\"schemaVersion\":1,\"stable\":null,\"beta\":null}", true));
        assertThrows(IOException.class, () -> parse("{\"schemaVersion\":1,\"stable\":null}", true));
        assertThrows(IOException.class, () -> parse("{\"schemaVersion\":1,\"stable\":"
            + manifest("1.0.0", 100, "beta") + ",\"beta\":null}", true));
    }

    @Test public void feedCacheIsSeparateFromApiAndBoundToUrl() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore store = new UpdateMetadataStore(context);
        store.save("[]", "api");
        store.saveFeed("https://example.invalid/feed", "feed", "feed-tag");
        assertEquals("api", store.cacheSnapshot().etag);
        assertEquals("feed-tag", store.feedSnapshot("https://example.invalid/feed").etag);
        assertNull(store.feedSnapshot("https://example.invalid/other").body);
    }
}
