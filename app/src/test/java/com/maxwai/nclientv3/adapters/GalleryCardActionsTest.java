// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryCardActionsTest {
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
    public void simpleGalleryCanBeFavoritedWithoutResolvingNetworkDetails() throws Exception {
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(RuntimeEnvironment.getApplication(), new JSONObject()
            .put("id", 456)
            .put("media_id", "789")
            .put("thumbnail", "abc/1.jpg")
            .put("english_title", "Offline gallery")
            .put("num_pages", 4));
        Queries.FavoriteTable.addFavorite(gallery);
        assertTrue(Queries.FavoriteTable.isFavorite(456));
    }
}
