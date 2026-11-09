// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DebugImageMaskTest {
    @Test
    public void defaultMaskPreservesDimensionsWithoutDrawingSource() {
        Application application = RuntimeEnvironment.getApplication();
        ColorDrawable source = new ColorDrawable(Color.RED) {
            @Override public int getIntrinsicWidth() { return 120; }
            @Override public int getIntrinsicHeight() { return 240; }
            @Override public void draw(Canvas canvas) { fail("Source image must not be drawn"); }
        };
        assertTrue(DebugImageMask.isEnabled(application));
        Drawable mask = DebugImageMask.wrap(application, source);
        assertNotNull(mask);
        assertEquals(120, mask.getIntrinsicWidth());
        assertEquals(240, mask.getIntrinsicHeight());
        mask.setBounds(0, 0, 120, 240);
        mask.setAlpha(0);
        Bitmap bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888);
        mask.draw(new Canvas(bitmap));
        assertNotEquals(Color.RED, bitmap.getPixel(10, 10));
        assertEquals(255, Color.alpha(bitmap.getPixel(10, 10)));
        DebugImageMask.setEnabled(application, false);
        assertSame(source, DebugImageMask.wrap(application, source));
        assertNull(DebugImageMask.wrap(application, null));
        assertFalse(application.getSharedPreferences("DebugDisplay", 0).getBoolean("mask_images", true));
    }
}
