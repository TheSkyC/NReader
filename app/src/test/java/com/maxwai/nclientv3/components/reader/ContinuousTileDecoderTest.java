// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileDecoderTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void decodesRegionsAndRejectsInvalidOrClosedRequests() throws Exception {
        File file = temporaryFolder.newFile("page.png");
        Bitmap source = Bitmap.createBitmap(1024, 48, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        source.setPixel(9, 13, Color.BLACK);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        source.recycle();

        ContinuousTileDecoder decoder = ContinuousTileDecoder.open(file);
        assertEquals(1024, decoder.width());
        assertEquals(48, decoder.height());
        ContinuousTileGrid grid = new ContinuousTileGrid(1024, 48, 512, 1);
        Rect planned = grid.visibleTiles(new Rect(0, 0, 1024, 48), 2).get(0);
        Bitmap plannedTile = decoder.decode(planned, 1);
        assertEquals(512, plannedTile.getWidth());
        plannedTile.recycle();
        Bitmap tile = decoder.decode(new Rect(8, 12, 16, 20), 1);
        assertEquals(8, tile.getWidth());
        assertEquals(8, tile.getHeight());
        // Robolectric's region-decoder shadow does not expose decoded pixels.
        tile.recycle();
        Bitmap sampled = decoder.decode(new Rect(0, 0, 32, 48), 2);
        // The region-decoder shadow ignores inSampleSize; device coverage checks it.
        assertNotNull(sampled);
        sampled.recycle();
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new Rect(-1, 0, 8, 8), 1));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new Rect(0, 0, 8, 8), 3));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new Rect(0, 0, 513, 8), 1));
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.decode(new Rect(0, 0, 8, 8), 1));
    }
}
