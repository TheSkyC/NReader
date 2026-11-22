package com.theskyc.nreader.async;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.theskyc.nreader.api.components.TagSuggestion;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.async.database.TagTranslationManifest;
import com.theskyc.nreader.settings.TagV2;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TagTranslationFeedTest {
    private static final String POINTER = "https://feed.example:8443/tags/manifest.json";
    private static final String PACK = "{\"schemaVersion\":1,\"locale\":\"ja\",\"revision\":1,"
        + "\"entries\":[{\"id\":8378,\"name\":\"\u753b\u5bb6\",\"aliases\":[]}]}\n";

    @Rule public TemporaryFolder files = new TemporaryFolder();
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    @Before public void openDatabase() {
        helper = new DatabaseHelper(RuntimeEnvironment.getApplication());
        database = helper.getWritableDatabase();
        database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (8378, 'canonical', 3, 100)");
    }

    @After public void closeDatabase() {
        helper.close();
    }

    @Test public void listsWithoutDownloadingThenStagesOnlyTheSelectedPack() throws Exception {
        List<String> requests = new ArrayList<>();
        String digest = sha256(PACK);
        String otherPack = PACK.replace("\"ja\"", "\"zh-Hant\"");
        String index = manifest(digest, 1, PACK, descriptor("zh-Hant", sha256(otherPack), 1, otherPack));
        TagTranslationFeed feed = feed(chain -> {
            requests.add(chain.request().url().toString());
            assertNull(chain.request().header("Cookie"));
            assertNull(chain.request().header("Authorization"));
            assertEquals("identity", chain.request().header("Accept-Encoding"));
            return response(chain, 200, body(requests.size() == 1 ? index : PACK));
        });
        TagTranslationManifest listing = feed.readManifest();
        assertEquals(2, listing.getPacks().size());
        TagTranslationManifest.Pack pack = listing.getPacks().get("ja");
        assertEquals(List.of(POINTER), requests);
        assertFalse(stagingDirectory().exists());
        assertTrue(stagingDirectory().mkdir());
        File abandoned = new File(stagingDirectory(), "translation-abandoned.part");
        assertTrue(abandoned.createNewFile());

        assertTrue(feed.install(database, pack));
        assertFalse(feed.install(database, pack));
        assertEquals(List.of(POINTER, "https://feed.example:8443/tags/translation-" + digest + ".json"), requests);
        assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEmptyStagingDirectory();
    }

    @Test public void installsReviewedReleaseFixtureAndSearchesAliasUsingCanonicalIdentity() throws Exception {
        String manifest = releaseFixture("manifest.json");
        String data = releaseFixture("pack.json");
        String expectedPayloadName = "translation-" + sha256(data) + ".json";
        TagV2.initMinCount(RuntimeEnvironment.getApplication());
        TagTranslationFeed feed = feed(chain -> {
            String name = chain.request().url().pathSegments().get(1);
            if ("manifest.json".equals(name)) return response(chain, 200, body(manifest));
            assertEquals(expectedPayloadName, name);
            return response(chain, 200, body(data));
        });
        assertTrue(feed.install(database, feed.readManifest().getPacks().get("ja")));
        assertEquals(7, TagTranslationCatalog.installedRevision(database, "ja"));
        List<TagSuggestion> results = Queries.TagTable.getTagSuggestions(TagType.TAG, "CAFE\u0301", Locale.JAPAN);
        assertEquals(1, results.size());
        assertEquals(8378, results.get(0).getTag().getId());
        assertEquals("\u753b\u5bb6", results.get(0).getDisplayName());
        assertEquals("tag:\"canonical\"", results.get(0).getTag().toQueryTag());
        assertEmptyStagingDirectory();
    }

    @Test public void publicClientCannotFollowRedirectsOrUseAccountState() {
        OkHttpClient client = TagFeedDownload.publicClient();
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertSame(CookieJar.NO_COOKIES, client.cookieJar());
        assertSame(Authenticator.NONE, client.authenticator());
        assertTrue(client.interceptors().isEmpty());
        assertTrue(client.networkInterceptors().isEmpty());
    }

    @Test public void rejectsRedirectResponsesAndCompressedOrUnboundedManifestBodies() throws Exception {
        TagTranslationFeed redirect = feed(chain -> response(chain, 302, body(""))
            .newBuilder().header("Location", "https://other.example/manifest.json").build());
        assertThrows(IOException.class, redirect::readManifest);

        TagTranslationFeed compressed = feed(chain -> response(chain, 200, body(""))
            .newBuilder().header("Content-Encoding", "gzip").build());
        assertThrows(IOException.class, compressed::readManifest);

        TagTranslationFeed oversized = feed(chain ->
            response(chain, 200, body(" ".repeat(TagTranslationManifest.MAX_BYTES + 1))));
        assertThrows(IOException.class, oversized::readManifest);
        assertFalse(stagingDirectory().exists());
    }

    @Test public void truncatedOversizedOrFailedDownloadLeavesInstalledPackAndNoPartialFile() throws Exception {
        String update = PACK.replace("\"revision\":1", "\"revision\":2");
        String digest = sha256(update);
        TagTranslationManifest.Pack pack = TagTranslationManifest.parse(bytes(manifest(digest, 2, update)))
            .getPacks().get("ja");
        assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(bytes(PACK)), () -> false));
        for (String invalid : List.of(update.substring(1), update + " ")) {
            TagTranslationFeed feed = feed(chain -> response(chain, 200, body(invalid)));
            assertThrows(IOException.class, () -> feed.install(database, pack));
            assertEquals(1, TagTranslationCatalog.installedRevision(database, "ja"));
            assertEmptyStagingDirectory();
        }
        TagTranslationFeed interrupted = feed(chain -> { throw new IOException("Synthetic disconnect"); });
        assertThrows(IOException.class, () -> interrupted.install(database, pack));
        assertEmptyStagingDirectory();
    }

    @Test public void cancelsAnActivePackCallAndDiscardsStaging() throws Exception {
        String digest = sha256(PACK);
        TagTranslationManifest.Pack pack = TagTranslationManifest.parse(bytes(manifest(digest, 1, PACK)))
            .getPacks().get("ja");
        AtomicReference<TagTranslationFeed> current = new AtomicReference<>();
        TagTranslationFeed feed = feed(chain -> {
            current.get().cancel();
            assertTrue(chain.call().isCanceled());
            return response(chain, 200, body(PACK));
        });
        current.set(feed);
        assertThrows(IOException.class, () -> feed.install(database, pack));
        assertThrows(IOException.class, feed::readManifest);
        assertEquals(0, TagTranslationCatalog.installedRevision(database, "ja"));
        assertEmptyStagingDirectory();
    }

    private TagTranslationFeed feed(Interceptor server) throws IOException {
        return new TagTranslationFeed(files.getRoot(), POINTER, new OkHttpClient.Builder().addInterceptor(server).build());
    }

    private static String releaseFixture(String name) throws IOException {
        try (InputStream input = TagTranslationFeedTest.class.getResourceAsStream("/tag-translation-release/" + name)) {
            if (input == null) throw new IOException("Missing translation release fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private File stagingDirectory() {
        return new File(files.getRoot(), "tag-translation-feed");
    }

    private void assertEmptyStagingDirectory() {
        assertEquals(0, stagingDirectory().list().length);
    }

    private static String manifest(String digest, int revision, String payload) {
        return manifest(digest, revision, payload, "");
    }

    private static String manifest(String digest, int revision, String payload, String otherDescriptor) {
        return "{\"schemaVersion\":1,\"source\":\"nreader-translations\",\"kind\":\"translations\",\"packs\":["
            + descriptor("ja", digest, revision, payload)
            + (otherDescriptor.isEmpty() ? "" : "," + otherDescriptor) + "]}";
    }

    private static String descriptor(String locale, String digest, int revision, String payload) {
        return "{\"locale\":\"" + locale + "\",\"revision\":" + revision + ",\"sizeBytes\":" + bytes(payload).length
            + ",\"sha256\":\"" + digest + "\"}";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String value) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte digit : MessageDigest.getInstance("SHA-256").digest(bytes(value))) {
            hex.append(String.format(Locale.ROOT, "%02x", digit));
        }
        return hex.toString();
    }

    private static Response response(Interceptor.Chain chain, int status, ResponseBody body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("Synthetic response").body(body).build();
    }

    private static ResponseBody body(String value) {
        return new ResponseBody() {
            private final Buffer buffer = new Buffer().writeUtf8(value);
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return buffer; }
        };
    }
}
