// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.app.Application;

import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.classes.ReaderBookmark;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReaderBookmarkTest {
    @Test
    public void pageBookmarksRoundTripAndNormalizeInput() {
        Application application = RuntimeEnvironment.getApplication();
        try (DatabaseHelper helper = new DatabaseHelper(application)) {
            helper.getWritableDatabase();
            Queries.ReaderBookmarkTable.deleteForGallery(900001);
            assertTrue(Queries.ReaderBookmarkTable.upsert(900001, 3, "  Scene  ", "  note  ") > 0);
            List<ReaderBookmark> bookmarks = Queries.ReaderBookmarkTable.getForGallery(900001);
            assertEquals(1, bookmarks.size());
            assertEquals("Scene", bookmarks.get(0).title);
            assertEquals("note", bookmarks.get(0).note);
            assertEquals(3, bookmarks.get(0).page);
            Queries.ReaderBookmarkTable.delete(bookmarks.get(0).id);
            assertTrue(Queries.ReaderBookmarkTable.getForGallery(900001).isEmpty());
        }
    }
}
