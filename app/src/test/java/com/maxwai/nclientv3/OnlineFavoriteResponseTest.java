// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OnlineFavoriteResponseTest {
    @Test
    public void parsesFavoritedBooleanWithoutMatchingUnrelatedFields() {
        assertEquals(Boolean.TRUE, GalleryActivity.parseFavoriteResponse("{\"favorited\":true}"));
        assertEquals(Boolean.FALSE, GalleryActivity.parseFavoriteResponse(
            "{\"favorited\":false,\"other\":true}"));
    }

    @Test
    public void rejectsMissingWrongTypedMalformedAndEmptyResponses() {
        assertNull(GalleryActivity.parseFavoriteResponse("{\"other\":true}"));
        assertNull(GalleryActivity.parseFavoriteResponse("{\"favorited\":\"true\"}"));
        assertNull(GalleryActivity.parseFavoriteResponse("not json"));
        assertNull(GalleryActivity.parseFavoriteResponse(" "));
        assertNull(GalleryActivity.parseFavoriteResponse(null));
    }
}
