// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class ReaderAutoCropTransformationTest {
    @Test
    public void findsOpaqueWhiteMarginsAndKeepsSafetyPadding() {
        Bitmap bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for (int y = 12; y < 68; y++) {
            for (int x = 15; x < 85; x++) bitmap.setPixel(x, y, Color.BLACK);
        }
        Rect bounds = ReaderAutoCropTransformation.findContentBounds(bitmap);
        assertNotNull(bounds);
        assertEquals(14, bounds.left);
        assertEquals(11, bounds.top);
        assertEquals(86, bounds.right);
        assertEquals(69, bounds.bottom);
        bitmap.recycle();
    }

    @Test
    public void preservesUnsampledContentInLargeImageMargins() {
        Bitmap bitmap = Bitmap.createBitmap(1500, 1500, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for (int y = 300; y < 1200; y++) {
            for (int x = 300; x < 1200; x++) bitmap.setPixel(x, y, Color.BLACK);
        }
        assertNotNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.setPixel(51, 51, Color.BLACK);
        assertNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.setPixel(51, 51, Color.TRANSPARENT);
        assertNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.recycle();
    }

    @Test
    public void checksMarginsAcrossReadChunks() {
        Bitmap bitmap = Bitmap.createBitmap(5000, 500, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        new Canvas(bitmap).drawRect(1000, 100, 4000, 400, paint);
        assertNotNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.setPixel(4097, 51, Color.BLACK);
        assertNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.recycle();
    }

    @Test
    public void leavesLargeMarginsUncroppedWhenVerificationWouldExceedBudget() {
        Bitmap bitmap = Bitmap.createBitmap(3000, 1200, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        new Canvas(bitmap).drawRect(700, 270, 2300, 930, paint);
        assertNull(ReaderAutoCropTransformation.findContentBounds(bitmap));
        bitmap.recycle();
    }

    @Test
    public void leavesTransparentAndBlankImagesUnchanged() {
        Bitmap transparent = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        transparent.eraseColor(Color.TRANSPARENT);
        assertNull(ReaderAutoCropTransformation.findContentBounds(transparent));
        transparent.recycle();

        Bitmap blank = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        blank.eraseColor(Color.WHITE);
        assertNull(ReaderAutoCropTransformation.findContentBounds(blank));
        blank.recycle();
    }
}
