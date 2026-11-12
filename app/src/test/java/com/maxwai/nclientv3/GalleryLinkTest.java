// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class GalleryLinkTest {
    @Test
    public void acceptsOnlyTrustedGalleryLinksWithPositiveIds() {
        assertEquals(123456, GalleryActivity.galleryIdFromLink(Uri.parse(
            "https://nhentai.net/g/123456/")));
        assertEquals(3, GalleryActivity.galleryPageFromLink(Uri.parse(
            "https://nhentai.net/g/123456/3")));
        assertEquals(0, GalleryActivity.galleryPageFromLink(Uri.parse(
            "https://nhentai.net/g/123456")));

        assertEquals(-1, GalleryActivity.galleryIdFromLink(Uri.parse(
            "https://attacker.example/g/123456/")));
        assertEquals(-1, GalleryActivity.galleryIdFromLink(Uri.parse(
            "https://nhentai.net/search/123456/")));
        assertEquals(-1, GalleryActivity.galleryIdFromLink(Uri.parse(
            "https://nhentai.net/g/0/")));
        assertEquals(-1, GalleryActivity.galleryPageFromLink(Uri.parse(
            "https://nhentai.net/g/123456/not-a-page")));
        assertEquals(-1, GalleryActivity.galleryPageFromLink(Uri.parse(
            "https://nhentai.net/g/123456/0")));
    }
}
