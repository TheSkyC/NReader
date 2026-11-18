// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderImageFilterTest {
    @Test
    public void invalidFilterValuesFallBackToOriginal() {
        assertEquals(ReaderImageFilter.NONE, ReaderImageFilter.sanitize(-1));
        assertEquals(ReaderImageFilter.NONE, ReaderImageFilter.sanitize(99));
        assertEquals(ReaderImageFilter.GRAYSCALE, ReaderImageFilter.sanitize(ReaderImageFilter.GRAYSCALE));
        assertEquals(ReaderImageFilter.SEPIA, ReaderImageFilter.sanitize(ReaderImageFilter.SEPIA));
    }

    @Test
    public void imageAdjustmentsAreClampedToTheReaderRange() {
        assertEquals(-100, ReaderImageFilter.sanitizeAdjustment(-500));
        assertEquals(100, ReaderImageFilter.sanitizeAdjustment(500));
        assertEquals(0, ReaderImageFilter.sanitizeAdjustment(0));
    }

    @Test
    public void saturationAlsoAppliesToInvertAndSepia() {
        for (int mode : new int[]{ReaderImageFilter.NONE, ReaderImageFilter.INVERT, ReaderImageFilter.SEPIA}) {
            int original = filteredPixel(mode, 0, 0, 0);
            int desaturated = filteredPixel(mode, 0, 0, -100);
            assertNotEquals(original, desaturated);
            assertEquals(Color.red(desaturated), Color.green(desaturated), 1);
            assertEquals(Color.green(desaturated), Color.blue(desaturated), 1);
        }
    }

    @Test
    public void brightnessAndContrastApplyWithoutChangingAlpha() {
        int baseline = filteredPixel(ReaderImageFilter.INVERT, 0, 0, 0);
        int adjusted = filteredPixel(ReaderImageFilter.INVERT, 10, 20, 0);
        assertNotEquals(baseline, adjusted);
        assertEquals(128, Color.alpha(baseline));
        assertEquals(Color.alpha(baseline), Color.alpha(adjusted));
        ImageView image = new ImageView(RuntimeEnvironment.getApplication());
        ReaderImageFilter.apply(image, ReaderImageFilter.NONE, 0, 0, 0);
        assertNull(image.getColorFilter());
    }

    private int filteredPixel(int mode, int brightness, int contrast, int saturation) {
        ImageView image = new ImageView(RuntimeEnvironment.getApplication());
        ReaderImageFilter.apply(image, mode, brightness, contrast, saturation);
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 50, 110, 180));
        paint.setColorFilter(image.getColorFilter());
        new Canvas(bitmap).drawColor(Color.TRANSPARENT);
        new Canvas(bitmap).drawPoint(0, 0, paint);
        int result = bitmap.getPixel(0, 0);
        bitmap.recycle();
        return result;
    }
}
