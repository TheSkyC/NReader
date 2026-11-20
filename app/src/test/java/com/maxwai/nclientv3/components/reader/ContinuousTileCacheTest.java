// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileCacheTest {
    @Test
    public void evictsLeastRecentlyUsedWithoutRecyclingAndSeparatesSessions() {
        Bitmap first = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap third = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ContinuousTileCache cache = new ContinuousTileCache(first.getAllocationByteCount() * 2, 2,
            first.getAllocationByteCount());
        ContinuousTileCache.Session session = cache.newSession();
        Rect region = new Rect(0, 0, 8, 8);
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(session, region, 1, first));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(session, region, 2, second));
        assertSame(first, cache.get(session, region, 1));
        ContinuousTileCache.Session other = cache.newSession();
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(other, region, 1, third));
        assertNull(cache.get(session, region, 2));
        assertFalse(second.isRecycled());
        assertSame(first, cache.get(session, region, 1));
        cache.removeSession(session);
        assertNull(cache.get(session, region, 1));
        assertEquals(ContinuousTileCache.StoreResult.INVALID, cache.put(session, region, 1, first));
        assertSame(third, cache.get(other, region, 1));
        cache.clear();
        assertNull(cache.get(other, region, 1));
        first.recycle();
        second.recycle();
        third.recycle();
    }

    @Test
    public void rejectsOversizedAndForgetsExternallyRecycledBitmap() {
        Bitmap small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap large = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        ContinuousTileCache cache = new ContinuousTileCache(small.getAllocationByteCount(), 4,
            small.getAllocationByteCount());
        ContinuousTileCache.Session session = cache.newSession();
        Rect region = new Rect(0, 0, 8, 8);
        assertEquals(ContinuousTileCache.StoreResult.TOO_LARGE, cache.put(session, region, 1, large));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(session, region, 1, small));
        small.recycle();
        assertNull(cache.get(session, region, 1));
        assertEquals(ContinuousTileCache.StoreResult.INVALID, cache.put(session, region, 1, small));
        large.recycle();
    }

    @Test
    public void enforcesByteLimitAndCopiesMutableRegionKeys() {
        Bitmap first = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ContinuousTileCache cache = new ContinuousTileCache(first.getAllocationByteCount(), 4,
            first.getAllocationByteCount());
        ContinuousTileCache.Session session = cache.newSession();
        Rect region = new Rect(0, 0, 8, 8);
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(session, region, 1, first));
        region.offset(8, 0);
        assertSame(first, cache.get(session, new Rect(0, 0, 8, 8), 1));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(session, region, 1, second));
        assertNull(cache.get(session, new Rect(0, 0, 8, 8), 1));
        assertSame(second, cache.get(session, region, 1));
        cache.clear();
        first.recycle();
        second.recycle();
    }

    @Test
    public void sharesBudgetWithoutOneSessionEvictingAnother() {
        Bitmap tile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        int tileBytes = tile.getAllocationByteCount();
        ContinuousTileCache cache = new ContinuousTileCache(tileBytes * 4, 4, tileBytes);
        ContinuousTileCache.Session first = cache.newSession();
        ContinuousTileCache.Session second = cache.newSession();
        Rect firstRegion = new Rect(0, 0, 8, 8);
        Rect secondRegion = new Rect(8, 0, 16, 8);
        Rect thirdRegion = new Rect(16, 0, 24, 8);
        assertEquals(2, cache.capacity(first));
        assertEquals(2, cache.capacity(second));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(first, firstRegion, 1, tile));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(first, secondRegion, 1, tile));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(second, firstRegion, 1, tile));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(second, secondRegion, 1, tile));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(first, thirdRegion, 1, tile));
        assertNull(cache.get(first, firstRegion, 1));
        assertSame(tile, cache.get(second, firstRegion, 1));
        assertSame(tile, cache.get(second, secondRegion, 1));
        cache.removeSession(second);
        assertEquals(4, cache.capacity(first));
        cache.clear();
        tile.recycle();
    }

    @Test
    public void unavailableSessionCanStoreAfterAnotherSessionCloses() {
        Bitmap tile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        int tileBytes = tile.getAllocationByteCount();
        ContinuousTileCache cache = new ContinuousTileCache(tileBytes, 1, tileBytes);
        ContinuousTileCache.Session first = cache.newSession();
        ContinuousTileCache.Session second = cache.newSession();
        Rect region = new Rect(0, 0, 8, 8);
        assertEquals(1, cache.capacity(first));
        assertEquals(0, cache.capacity(second));
        assertEquals(ContinuousTileCache.StoreResult.NO_CAPACITY, cache.put(second, region, 1, tile));
        cache.removeSession(first);
        assertEquals(1, cache.capacity(second));
        assertEquals(ContinuousTileCache.StoreResult.STORED, cache.put(second, region, 1, tile));
        cache.removeSession(second);
        tile.recycle();
    }

    @Test
    public void clearingCacheNotifiesActiveSessionsToRedraw() {
        ContinuousTileCache cache = new ContinuousTileCache(1024, 4, 256);
        AtomicInteger callbacks = new AtomicInteger();
        Runnable listener = callbacks::incrementAndGet;
        ContinuousTileCache.Session session = cache.newSession(listener);
        cache.clear();
        assertEquals(1, callbacks.get());
        cache.removeSession(session);
        cache.clear();
        assertEquals(1, callbacks.get());
    }
}
