// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.classes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.enums.ApiRequestType;
import com.maxwai.nclientv3.api.enums.SortType;
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
    public void tagBookmarkReadsSortFromQueryParameter() {
        Bookmark bookmark = new Bookmark("https://nhentai.net/api/v2/search?query=language%3Aenglish&page=2&sort=popular-month",
            2, ApiRequestType.BYTAG, SpecialTagIds.LANGUAGE_ENGLISH);
        InspectorV3 inspector = bookmark.createInspector(RuntimeEnvironment.getApplication(), null);
        assertEquals("popular-month", Uri.parse(inspector.getUrl()).getQueryParameter("sort"));
        assertEquals("2", Uri.parse(inspector.getUrl()).getQueryParameter("page"));
    }
}
