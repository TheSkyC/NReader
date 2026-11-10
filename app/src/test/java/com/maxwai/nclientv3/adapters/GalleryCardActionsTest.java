// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.adapters;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.os.Parcel;

import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
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
        Gallery stored = Queries.GalleryTable.galleryFromId(RuntimeEnvironment.getApplication(), 456);
        assertTrue(stored.getGalleryData().isIncomplete());
        assertFalse(stored.getGalleryData().hasUpdatedInfo());
        assertEquals(4, stored.getPageCount());
        assertEquals(gallery.getThumbnail(), stored.getThumbnail());
        assertEquals(0, stored.getGalleryData().getPages().size());

        Parcel parcel = Parcel.obtain();
        stored.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Gallery restored = Gallery.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        assertTrue(restored.getGalleryData().isIncomplete());
        assertEquals(4, restored.getPageCount());

        Queries.FavoriteTable.removeFavorite(456);
        Queries.FavoriteTable.addFavorite((GenericGallery) stored);
        assertTrue(Queries.FavoriteTable.isFavorite(456));
        assertEquals(4, Queries.GalleryTable.galleryFromId(RuntimeEnvironment.getApplication(), 456).getPageCount());
    }

    @Test
    public void completeGallerySavedThroughGenericReferenceKeepsItsDetails() throws Exception {
        GenericGallery gallery = new Gallery(null, "{\"id\":457,\"num_pages\":1,\"title\":{\"pretty\":\"Full\"},"
            + "\"cover\":{\"path\":\"galleries/1/cover.png\"},\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\"}]}", null, false);
        Queries.FavoriteTable.addFavorite(gallery);
        Gallery stored = Queries.GalleryTable.galleryFromId(RuntimeEnvironment.getApplication(), 457);
        assertTrue(Queries.FavoriteTable.isFavorite(457));
        assertFalse(stored.getGalleryData().isIncomplete());
        assertEquals(1, stored.getGalleryData().getPages().size());
    }

    @Test
    public void previouslySavedMinimalRecordRetainsItsPageCount() throws Exception {
        SimpleGallery gallery = SimpleGallery.fromV2ListItem(RuntimeEnvironment.getApplication(), new JSONObject()
            .put("id", 458).put("media_id", "789").put("thumbnail", "abc/1.jpg")
            .put("english_title", "Earlier favorite").put("num_pages", 4));
        Queries.FavoriteTable.addFavorite(gallery);
        String image = gallery.getThumbnail().toString();
        helper.getWritableDatabase().execSQL("UPDATE Gallery SET pages = ? WHERE idGallery = ?",
            new Object[]{"4;" + image + ";" + image + ";", 458});
        Gallery stored = Queries.GalleryTable.galleryFromId(RuntimeEnvironment.getApplication(), 458);
        assertTrue(stored.getGalleryData().isIncomplete());
        assertEquals(4, stored.getPageCount());
        assertFalse(stored.getGalleryData().hasUpdatedInfo());
    }
}
