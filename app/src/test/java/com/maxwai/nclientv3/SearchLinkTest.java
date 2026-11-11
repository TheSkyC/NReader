// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.enums.SpecialTagIds;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SearchLinkTest {
    private DatabaseHelper helper;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("Entries.db");
        helper = new DatabaseHelper(RuntimeEnvironment.getApplication());
        helper.getWritableDatabase();
        Queries.TagTable.updateStatus(SpecialTagIds.LANGUAGE_ENGLISH, TagStatus.ACCEPTED);
    }

    @After
    public void tearDown() { helper.close(); }

    @Test
    public void sharedSearchKeepsExactQuerySortAndPageWithoutDefaultTags() {
        Uri link = new Uri.Builder().scheme("https").authority("nhentai.net").path("/search/")
            .appendQueryParameter("q", "artist:example -tag:other")
            .appendQueryParameter("sort", "popular-week").appendQueryParameter("page", "7").build();
        InspectorV3 inspector = MainActivity.searchLinkInspector(RuntimeEnvironment.getApplication(), link, null);
        Uri request = Uri.parse(inspector.getUrl());
        assertEquals("artist:example -tag:other", request.getQueryParameter("query"));
        assertEquals("popular-week", request.getQueryParameter("sort"));
        assertEquals("7", request.getQueryParameter("page"));
    }

    @Test
    public void invalidPageFallsBackToOne() {
        for (String page : new String[]{"invalid", "0", "-3", "999999999999999"}) {
            Uri link = Uri.parse("https://nhentai.net/search/?q=test&page=" + page);
            assertEquals(1, MainActivity.searchLinkPage(link));
        }
        assertEquals(1, MainActivity.searchLinkPage(Uri.parse("https://nhentai.net/search/?q=test")));
    }

    @Test
    public void sharedLinksRequireTheSiteOrigin() {
        assertTrue(MainActivity.isTrustedLink(Uri.parse("https://nhentai.net/search/?q=test")));
        assertTrue(MainActivity.isTrustedLink(Uri.parse("http://nhentai.net/search/?q=test")));
        assertFalse(MainActivity.isTrustedLink(Uri.parse("https://nhentai.net.attacker.example/search/?q=test")));
        assertFalse(MainActivity.isTrustedLink(Uri.parse("https://attacker.example/search/?q=test")));
        assertFalse(MainActivity.isTrustedLink(Uri.parse("https://user@nhentai.net/search/?q=test")));
        assertFalse(MainActivity.isTrustedLink(Uri.parse("https://nhentai.net:8443/search/?q=test")));
    }
}
