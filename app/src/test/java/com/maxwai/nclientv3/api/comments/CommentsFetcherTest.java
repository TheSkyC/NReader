// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.api.comments;

import android.util.JsonReader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentsFetcherTest {
    @Test
    public void parsesPaginatedCommentsAndUser() throws IOException {
        String json = "{\"num_pages\":2,\"result\":[{\"id\":17,\"post_date\":1700000000,"
            + "\"body\":\"Hello\",\"poster\":{\"id\":4,\"username\":\"reader\","
            + "\"avatar_url\":\"\"}}],\"total\":51}";
        List<Comment> comments = new ArrayList<>();
        assertEquals(2, CommentsFetcher.readPage(new JsonReader(new StringReader(json)), comments));
        assertEquals(1, comments.size());
        assertEquals("reader", comments.get(0).getUsername());
        assertEquals("Hello", comments.get(0).getComment());
        assertNull(comments.get(0).getAvatarUrl());
    }

    @Test
    public void parsesEmptyPage() throws IOException {
        List<Comment> comments = new ArrayList<>();
        assertEquals(0, CommentsFetcher.readPage(new JsonReader(new StringReader("{\"result\":[],\"num_pages\":0}")), comments));
        assertEquals(0, comments.size());
    }

    @Test
    public void rejectsMissingPageMetadataAndInvalidComments() {
        assertThrows(IOException.class, () -> CommentsFetcher.readPage(
            new JsonReader(new StringReader("{\"result\":[]}")), new ArrayList<>()));
        assertThrows(IOException.class, () -> CommentsFetcher.readPage(
            new JsonReader(new StringReader("{\"result\":[{\"id\":1}],\"num_pages\":1}")), new ArrayList<>()));
    }
}
