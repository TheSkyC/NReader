// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.classes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.Language;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class BookmarkTest {
    private DatabaseHelper helper;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("Entries.db");
        helper = new DatabaseHelper(RuntimeEnvironment.getApplication());
        helper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void searchBookmarkRestoresCompleteQuerySortAndPage() {
        String query = "artist:example -tag:excluded pages:>=20 uploaded:<=2w";
        String savedUrl = new Uri.Builder().scheme("https").authority("nhentai.net")
            .path("/api/v2/search").appendQueryParameter("query", query)
            .appendQueryParameter("page", "3").appendQueryParameter("sort", "popular-week").build().toString();
        Bookmark bookmark = new Bookmark(savedUrl, 3, ApiRequestType.BYSEARCH, 0);
        InspectorV3 inspector = bookmark.createInspector(RuntimeEnvironment.getApplication(), null);
        assertEquals(SortType.POPULAR_WEEKLY, inspector.getSortType());
        Uri restored = Uri.parse(inspector.getUrl());
        assertEquals(query, restored.getQueryParameter("query"));
        assertEquals("popular-week", restored.getQueryParameter("sort"));
        assertEquals("3", restored.getQueryParameter("page"));

        inspector.setPage(4);
        restored = Uri.parse(inspector.getUrl());
        assertEquals(query, restored.getQueryParameter("query"));
        assertEquals("popular-week", restored.getQueryParameter("sort"));
        assertEquals("4", restored.getQueryParameter("page"));
    }

    @Test
    public void sortNamesMatchWholeValue() {
        assertEquals(SortType.POPULAR_ALL_TIME, SortType.findFromAddition("popular"));
        assertEquals(SortType.POPULAR_WEEKLY, SortType.findFromAddition("popular-week"));
        assertEquals(SortType.POPULAR_DAILY, SortType.findFromAddition("popular-today"));
        assertEquals(SortType.POPULAR_MONTH, SortType.findFromAddition("popular-month"));
        assertEquals(SortType.RECENT_ALL_TIME, SortType.findFromAddition("unpopular"));
    }

    @Test
    public void customTagBookmarkReplaysQueryRatherThanResolvingAnId() {
        Tag term = new Tag("unknown term", 0, SpecialTagIds.INVALID_ID, TagType.TAG, TagStatus.ACCEPTED);
        InspectorV3 original = InspectorV3.searchInspector(RuntimeEnvironment.getApplication(), "",
            Collections.singleton(term), 3, SortType.POPULAR_WEEKLY, null, null);
        Queries.BookmarkTable.addBookmark(original);
        Bookmark bookmark = Queries.BookmarkTable.getBookmarks().get(0);

        InspectorV3 replay = bookmark.createInspector(RuntimeEnvironment.getApplication(), null);
        Uri restored = Uri.parse(replay.getUrl());
        assertEquals(term.toQueryTag(), restored.getQueryParameter("query").trim());
        assertEquals(ApiRequestType.BYSEARCH, replay.getRequestType());
        assertEquals(0, bookmark.tag);
        assertEquals("3", restored.getQueryParameter("page"));
        assertEquals("popular-week", restored.getQueryParameter("sort"));
    }

    @Test
    public void customTagsWithDifferentTermsBothReachTheQuery() {
        Tag first = new Tag("first", 0, SpecialTagIds.INVALID_ID, TagType.TAG, TagStatus.ACCEPTED);
        Tag second = new Tag("second", 0, SpecialTagIds.INVALID_ID, TagType.TAG, TagStatus.AVOIDED);
        InspectorV3 inspector = InspectorV3.searchInspector(RuntimeEnvironment.getApplication(), "",
            List.of(first, second), 1, SortType.RECENT_ALL_TIME, null, null);
        String query = Uri.parse(inspector.getUrl()).getQueryParameter("query");
        assertTrue(query.contains(first.toQueryTag()));
        assertTrue(query.contains(second.toQueryTag()));
    }

    @Test
    public void siteTagBookmarkFollowsCanonicalRename() {
        Application app = RuntimeEnvironment.getApplication();
        Global.updateOnlyLanguage(app, Language.ALL);
        helper.getWritableDatabase().execSQL(
            "INSERT INTO Tags (idTag, name, type, count) VALUES (100000000, 'old name', 3, 100)");
        Tag siteTag = Queries.TagTable.getTagById(100000000);
        InspectorV3 original = InspectorV3.searchInspector(app, "",
            Collections.singleton(siteTag), 2, SortType.RECENT_ALL_TIME, null, null);
        Queries.BookmarkTable.addBookmark(original);
        helper.getWritableDatabase().execSQL("UPDATE Tags SET name = 'new name' WHERE idTag = 100000000");
        helper.getWritableDatabase().execSQL(
            "INSERT INTO TagRules (idTag, name, type, status) VALUES (100000000, 'new name', 3, 1)");
        Global.updateOnlyLanguage(app, Language.ENGLISH);
        try {
            Bookmark bookmark = Queries.BookmarkTable.getBookmarks().get(0);
            InspectorV3 replay = bookmark.createInspector(app, null);
            assertEquals(ApiRequestType.BYTAG, replay.getRequestType());
            assertEquals(100000000, replay.getTag().getId());
            assertEquals("new name", replay.getTag().getName());
            assertEquals("tag:\"new name\"", Uri.parse(replay.getUrl()).getQueryParameter("query").trim());
            assertEquals(TagStatus.AVOIDED, Queries.TagTable.getTagById(100000000).getStatus());
        } finally {
            Global.updateOnlyLanguage(app, Language.ALL);
        }
    }

    @Test
    public void missingTagBookmarkCanReplayItsSavedQuery() {
        Bookmark bookmark = new Bookmark("https://nhentai.net/api/v2/search?query=tag%3A%22saved%22&page=3",
            3, ApiRequestType.BYTAG, 100000001);
        InspectorV3 replay = bookmark.createInspector(RuntimeEnvironment.getApplication(), null);
        assertEquals(ApiRequestType.BYSEARCH, replay.getRequestType());
        assertEquals("tag:\"saved\"", Uri.parse(replay.getUrl()).getQueryParameter("query"));
    }

    @Test
    public void missingTagAndEmptyQueryDoNotOpenUnfilteredResults() {
        Bookmark bookmark = new Bookmark("https://nhentai.net/api/v2/search?query=&page=3",
            3, ApiRequestType.BYTAG, 100000001);
        assertNull(bookmark.createInspector(RuntimeEnvironment.getApplication(), null));
    }

    @Test
    public void tagBookmarkReadsSortFromQueryParameter() {
        Bookmark bookmark = new Bookmark("https://nhentai.net/api/v2/search?query=language%3Aenglish&page=2&sort=popular-month",
            2, ApiRequestType.BYTAG, SpecialTagIds.LANGUAGE_ENGLISH);
        InspectorV3 inspector = bookmark.createInspector(RuntimeEnvironment.getApplication(), null);
        assertEquals("popular-month", Uri.parse(inspector.getUrl()).getQueryParameter("sort"));
        assertEquals("2", Uri.parse(inspector.getUrl()).getQueryParameter("page"));
    }
}
