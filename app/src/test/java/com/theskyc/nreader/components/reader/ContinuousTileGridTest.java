// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Application;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousTileGridTest {
    @Test
    public void sampleKeepsEnoughSourcePixelsForDisplay() {
        assertEquals(1, ContinuousTileGrid.sampleForScale(1.8f));
        assertEquals(1, ContinuousTileGrid.sampleForScale(0.75f));
        assertEquals(2, ContinuousTileGrid.sampleForScale(0.5f));
        assertEquals(4, ContinuousTileGrid.sampleForScale(0.2f));
        assertEquals(1 << 20, ContinuousTileGrid.sampleForScale(Float.MIN_VALUE));
        assertEquals(1, ContinuousTileGrid.sampleForScale(Float.NaN));
        assertEquals(1, ContinuousTileGrid.sampleForScale(0f));
    }

    @Test
    public void clipsAndCoversVisibleRegionWithoutTilesBeyondSource() {
        ContinuousTileGrid grid = new ContinuousTileGrid(600, 12000, 512, 1);
        List<Rect> tiles = grid.visibleTiles(new Rect(-10, 4800, 800, 6000), 20);
        assertEquals(6, tiles.size());
        assertEquals(new Rect(0, 5120, 512, 5632), tiles.get(0));
        assertTrue(tiles.contains(new Rect(512, 4608, 600, 5120)));
        assertTrue(tiles.contains(new Rect(512, 5632, 600, 6144)));
        assertTrue(grid.visibleTiles(new Rect(0, 12000, 600, 13000), 20).isEmpty());
    }

    @Test
    public void limitsWorkForHugeImagesAndUsesSampledTileSize() {
        ContinuousTileGrid grid = new ContinuousTileGrid(Integer.MAX_VALUE, Integer.MAX_VALUE, 512, 4);
        List<Rect> tiles = grid.visibleTiles(new Rect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE), 9);
        assertEquals(9, tiles.size());
        assertEquals(2048, tiles.get(0).width());
        assertEquals(2048, tiles.get(0).height());
        assertTrue(grid.visibleTiles(new Rect(0, 0, 100, 100), 0).isEmpty());
    }

    @Test
    public void tileCountIsBoundedAndRegionsAreDistinct() {
        ContinuousTileGrid grid = new ContinuousTileGrid(1_000_000, 1_000_000, 512, 1);
        List<Rect> tiles = grid.visibleTiles(new Rect(0, 0, 1_000_000, 1_000_000), Integer.MAX_VALUE);
        assertEquals(256, tiles.size());
        assertEquals(tiles.size(), new HashSet<>(tiles).size());
        assertTrue(tiles.get(0).contains(500_000, 500_000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPowerOfTwoSample() {
        new ContinuousTileGrid(600, 12000, 512, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTilesLargerThanDecoderLimit() {
        new ContinuousTileGrid(1200, 12000, 513, 1);
    }
}
