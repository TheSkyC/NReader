// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileDrawableTest {
    @Test
    public void visibleViewportKeepsLongPageAtFullSamplingAndFollowsScroll() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        Rect visible = new Rect(0, 0, 300, 400);
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 300, 6000, 1,
            source, new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); }, () -> new Rect(visible));
        drawable.setBounds(0, 0, 300, 6000);
        assertEquals(300, drawable.getIntrinsicWidth());
        assertEquals(6000, drawable.getIntrinsicHeight());
        Bitmap frame = Bitmap.createBitmap(300, 6000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        drawable.draw(canvas);
        worker.runNext();
        ui.runNext();
        assertEquals(1, source.samples.get(0).intValue());
        assertEquals(new Rect(0, 0, 300, 511), source.regions.get(0));

        visible.set(0, 3061, 300, 3400);
        drawable.draw(canvas);
        worker.runNext();
        ui.runNext();
        assertEquals(1, source.samples.get(1).intValue());
        assertEquals(new Rect(0, 3059, 300, 3571), source.regions.get(1));
        drawable.close();
        worker.runNext();
        frame.recycle();
    }

    @Test
    public void drawsFallbackUntilVisibleTilesAreAvailable() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        ContinuousTileDrawable drawable = create(source, worker, ui, 8);
        Bitmap frame = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        drawable.draw(canvas);
        assertEquals(Color.BLUE, frame.getPixel(100, 100));
        worker.runNext();
        ui.runNext();
        drawable.draw(canvas);
        assertEquals(Color.BLACK, frame.getPixel(100, 100));
        assertEquals(Color.BLUE, frame.getPixel(550, 100));
        worker.runNext();
        ui.runNext();
        drawable.draw(canvas);
        assertEquals(Color.BLACK, frame.getPixel(509, 100));
        assertEquals(Color.BLACK, frame.getPixel(510, 100));
        assertEquals(Color.BLACK, frame.getPixel(550, 100));
        drawable.close();
        worker.runNext();
        assertEquals(1, source.closes);
        frame.recycle();
    }

    @Test
    public void clipAndViewportTransformChooseVisibleSourceTiles() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        ContinuousTileDrawable drawable = create(source, worker, ui, 8);
        Bitmap frame = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.translate(0, -600);
        drawable.draw(canvas);
        worker.runNext();
        assertEquals(new Rect(0, 509, 511, 1021), source.regions.get(0));
        assertEquals(1, source.samples.get(0).intValue());
        drawable.close();
        worker.runNext();
        frame.recycle();
    }

    @Test
    public void increasesSamplingToFitVisibleTileBudget() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        ContinuousTileDrawable drawable = create(source, worker, ui, 1);
        Bitmap frame = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(frame));
        worker.runNext();
        assertEquals(2, source.samples.get(0).intValue());
        assertEquals(new Rect(0, 0, 600, 1022), source.regions.get(0));
        drawable.close();
        worker.runNext();
        frame.recycle();
    }

    @Test
    public void transparentTilesDoNotBlendWithTheirFallbackTwice() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.color = 0x80ff0000;
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(source.color), 600, 1200, 8,
            source, new ContinuousTileCache(8 * 1024 * 1024, 8, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); });
        drawable.setBounds(0, 0, 600, 1200);
        drawable.setAlpha(128);
        Bitmap frame = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.WHITE);
        drawable.draw(canvas);
        int expected = frame.getPixel(100, 100);
        worker.runNext();
        ui.runNext();
        canvas.drawColor(Color.WHITE);
        drawable.draw(canvas);
        assertEquals(expected, frame.getPixel(100, 100));
        assertEquals(expected, frame.getPixel(550, 100));
        for (int x = 508; x <= 512; x++) assertEquals("transparent tile seam x=" + x, expected, frame.getPixel(x, 100));
        drawable.close();
        worker.runNext();
        frame.recycle();
    }

    @Test
    public void scaledTileBoundaryMatchesFullImageFiltering() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.split = true;
        ContinuousTileDrawable drawable = create(source, worker, ui, 8);
        Bitmap actual = Bitmap.createBitmap(780, 520, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(actual);
        canvas.scale(1.3f, 1.3f);
        drawable.draw(canvas);
        worker.runNext();
        ui.runNext();
        drawable.draw(canvas);
        worker.runNext();
        ui.runNext();
        drawable.draw(canvas);

        Bitmap original = Bitmap.createBitmap(600, 1200, Bitmap.Config.ARGB_8888);
        Canvas originalCanvas = new Canvas(original);
        originalCanvas.drawColor(Color.BLACK);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        originalCanvas.drawRect(510, 0, 600, 1200, white);
        Bitmap expected = Bitmap.createBitmap(780, 520, Bitmap.Config.ARGB_8888);
        Canvas reference = new Canvas(expected);
        reference.scale(1.3f, 1.3f);
        reference.drawBitmap(original, null, new RectF(0, 0, 600, 1200), new Paint(Paint.FILTER_BITMAP_FLAG));
        for (int x = 660; x <= 666; x++) assertEquals("pixel x=" + x, expected.getPixel(x, 100), actual.getPixel(x, 100));
        drawable.close();
        worker.runNext();
        actual.recycle();
        expected.recycle();
        original.recycle();
    }

    @Test
    public void downsampledHorizontalTileBoundaryHasNoFallbackGap() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.splitHorizontal = true;
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(Color.MAGENTA), 600, 6000, 8,
            source, new ContinuousTileCache(8 * 1024 * 1024, 8, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); });
        drawable.setBounds(0, 0, 600, 6000);
        Bitmap actual = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(actual);
        canvas.translate(150f - 300f * .31f, 150f - 2040f * .31f);
        canvas.scale(.31f, .31f);
        for (int round = 0; round < 4; round++) {
            drawable.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
        }

        Bitmap original = Bitmap.createBitmap(600, 6000, Bitmap.Config.ARGB_8888);
        Canvas originalCanvas = new Canvas(original);
        originalCanvas.drawColor(Color.BLACK);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        originalCanvas.drawRect(0, 2040, 600, 6000, white);
        Bitmap expected = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas reference = new Canvas(expected);
        reference.translate(150f - 300f * .31f, 150f - 2040f * .31f);
        reference.scale(.31f, .31f);
        reference.drawBitmap(original, null, new RectF(0, 0, 600, 6000), new Paint(Paint.FILTER_BITMAP_FLAG));
        for (int x = 60; x < 240; x++) {
            int expectedPixel = expected.getPixel(x, 150);
            assertEquals("tile boundary pixel x=" + x, expectedPixel, actual.getPixel(x, 150));
            assertTrue("unexpected fallback at x=" + x, actual.getPixel(x, 150) != Color.MAGENTA);
        }
        drawable.close();
        worker.runNext();
        actual.recycle();
        expected.recycle();
        original.recycle();
    }

    @Test
    public void translucentDownsampledTilesMatchReferenceAtSeamsAndOuterEdges() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.color = 0x80ff0000;
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(Color.MAGENTA), 600, 6000, 8,
            source, new ContinuousTileCache(8 * 1024 * 1024, 8, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); });
        drawable.setBounds(0, 0, 600, 6000);
        drawable.setAlpha(128);
        Bitmap actual = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(actual);
        canvas.translate(150.37f - 300f * .31f, 150.29f - 2040f * .31f);
        canvas.scale(.31f, .31f);
        for (int round = 0; round < 4; round++) {
            actual.eraseColor(Color.WHITE);
            drawable.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
        }
        Bitmap sampled = Bitmap.createBitmap(300, 3000, Bitmap.Config.ARGB_8888);
        sampled.eraseColor(source.color);
        Bitmap expected = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        expected.eraseColor(Color.WHITE);
        Canvas reference = new Canvas(expected);
        reference.translate(150.37f - 300f * .31f, 150.29f - 2040f * .31f);
        reference.scale(.31f, .31f);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(128);
        reference.drawBitmap(sampled, null, new RectF(0, 0, 600, 6000), paint);
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) {
            int wanted = expected.getPixel(x, y);
            int pixel = actual.getPixel(x, y);
            int delta = Math.max(Math.abs(Color.red(pixel) - Color.red(wanted)),
                Math.max(Math.abs(Color.green(pixel) - Color.green(wanted)), Math.abs(Color.blue(pixel) - Color.blue(wanted))));
            assertTrue("tile pixel x=" + x + " y=" + y + " delta=" + delta, delta <= 1);
        }
        drawable.close();
        worker.runNext();
        actual.recycle();
        expected.recycle();
        sampled.recycle();
    }

    @Test
    public void oddImageDimensionsUseOneSampledPixelGridThroughTerminalTiles() {
        assertSampledTerminalEdges(2201, 6003, 2, .31f);
        assertSampledTerminalEdges(2201, 6003, 4, .17f);
        assertSampledTerminalEdges(2201, 6003, 8, .09f);
        assertSampledTerminalEdges(1023, 5103, 1, .73f);
        assertSampledTerminalEdges(1023, 5103, 8, .09f);
        assertSampledTerminalEdges(1023, 4099, 2, .31f);
        assertSampledTerminalEdges(4099, 1023, 2, .31f);
        assertSampledTerminalEdges(3, 4099, 8, .09f);
    }

    private static void assertSampledTerminalEdges(int width, int height, int sample, float scale) {
        Bitmap sampled = Bitmap.createBitmap(Math.max(1, width / sample), Math.max(1, height / sample), Bitmap.Config.ARGB_8888);
        int[] row = new int[sampled.getWidth()];
        for (int y = 0; y < sampled.getHeight(); y++) {
            for (int x = 0; x < row.length; x++) row[x] = Color.argb(128,
                Math.abs((x * 7) % 510 - 255), Math.abs((y * 11) % 510 - 255), Math.abs(((x + y) * 5) % 510 - 255));
            sampled.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileLoader.Source source = new ContinuousTileLoader.Source() {
            @NonNull @Override public Bitmap decode(@NonNull Rect region, int requestedSample) {
                assertEquals(sample, requestedSample);
                return Bitmap.createBitmap(sampled, region.left / sample, region.top / sample,
                    Math.max(1, region.width() / sample), Math.max(1, region.height() / sample));
            }
            @Override public void close() {}
        };
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(Color.MAGENTA), width, height, 8,
            source, new ContinuousTileCache(8 * 1024 * 1024, 8, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); });
        drawable.setBounds(0, 0, width, height);
        Bitmap actual = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(actual);
        float left = 150.37f - (width - 80) * scale;
        float top = 150.29f - (height - 80) * scale;
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        for (int round = 0; round < 9; round++) {
            actual.eraseColor(Color.WHITE);
            drawable.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
        }
        Bitmap expected = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        expected.eraseColor(Color.WHITE);
        Canvas reference = new Canvas(expected);
        reference.translate(left, top);
        reference.scale(scale, scale);
        reference.drawBitmap(sampled, null, new RectF(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) {
            int wanted = expected.getPixel(x, y);
            int pixel = actual.getPixel(x, y);
            int delta = Math.max(Math.abs(Color.red(pixel) - Color.red(wanted)),
                Math.max(Math.abs(Color.green(pixel) - Color.green(wanted)), Math.abs(Color.blue(pixel) - Color.blue(wanted))));
            assertTrue("sample=" + sample + " size=" + width + "x" + height + " pixel=" + x + "," + y + " delta=" + delta, delta <= 1);
        }
        drawable.close();
        worker.runNext();
        actual.recycle();
        expected.recycle();
        sampled.recycle();
    }

    @Test
    public void sharedCacheDoesNotCauseAlternatingPagesToDecodeForever() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource firstSource = new FakeSource();
        FakeSource secondSource = new FakeSource();
        ContinuousTileCache cache = new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES);
        ContinuousTileDrawable first = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 400, 400, 1,
            firstSource, cache, worker, ui, failure -> { throw new AssertionError(failure); });
        ContinuousTileDrawable second = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 400, 400, 1,
            secondSource, cache, worker, ui, failure -> { throw new AssertionError(failure); });
        CountingCallback callback = new CountingCallback();
        second.setCallback(callback);
        first.setBounds(0, 0, 400, 400);
        second.setBounds(0, 0, 400, 400);
        Bitmap frame = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        for (int round = 0; round < 5; round++) {
            first.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
            second.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
        }
        assertEquals(1, firstSource.regions.size());
        assertEquals(0, secondSource.regions.size());
        first.close();
        while (!worker.queue.isEmpty()) worker.runNext();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        while (!ui.queue.isEmpty()) ui.runNext();
        assertTrue(callback.invalidations > 0);
        second.draw(canvas);
        while (!worker.queue.isEmpty()) worker.runNext();
        while (!ui.queue.isEmpty()) ui.runNext();
        assertEquals(1, secondSource.regions.size());
        second.close();
        while (!worker.queue.isEmpty()) worker.runNext();
        frame.recycle();
    }

    @Test
    public void multiplePagesWithEnoughBudgetKeepTheirVisibleTiles() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource firstSource = new FakeSource();
        FakeSource secondSource = new FakeSource();
        ContinuousTileCache cache = new ContinuousTileCache(8 * 1024 * 1024, 4, ContinuousTileDecoder.MAX_TILE_BYTES);
        ContinuousTileDrawable first = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 600, 1200, 8,
            firstSource, cache, worker, ui, failure -> { throw new AssertionError(failure); });
        ContinuousTileDrawable second = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 600, 1200, 8,
            secondSource, cache, worker, ui, failure -> { throw new AssertionError(failure); });
        first.setBounds(0, 0, 600, 1200);
        second.setBounds(0, 0, 600, 1200);
        Bitmap frame = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        for (int round = 0; round < 6; round++) {
            first.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
            second.draw(canvas);
            while (!worker.queue.isEmpty()) worker.runNext();
            while (!ui.queue.isEmpty()) ui.runNext();
        }
        assertEquals(2, firstSource.regions.size());
        assertEquals(2, secondSource.regions.size());
        first.close();
        second.close();
        while (!worker.queue.isEmpty()) worker.runNext();
        frame.recycle();
    }

    @NonNull
    private static ContinuousTileDrawable create(FakeSource source, QueueExecutor worker, QueueExecutor ui, int limit) {
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(new ColorDrawable(Color.BLUE), 600, 1200, limit,
            source, new ContinuousTileCache(8 * 1024 * 1024, 8, ContinuousTileDecoder.MAX_TILE_BYTES), worker, ui,
            failure -> { throw new AssertionError(failure); });
        drawable.setBounds(0, 0, 600, 1200);
        return drawable;
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove().run(); }
    }

    private static final class CountingCallback implements Drawable.Callback {
        int invalidations;
        @Override public void invalidateDrawable(@NonNull Drawable who) { invalidations++; }
        @Override public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {}
        @Override public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {}
    }

    private static final class FakeSource implements ContinuousTileLoader.Source {
        final List<Rect> regions = new ArrayList<>();
        final List<Integer> samples = new ArrayList<>();
        int color = Color.BLACK;
        boolean split;
        boolean splitHorizontal;
        int closes;

        @NonNull @Override public Bitmap decode(@NonNull Rect region, int sample) {
            regions.add(new Rect(region));
            samples.add(sample);
            Bitmap bitmap = Bitmap.createBitmap((region.width() + sample - 1) / sample,
                (region.height() + sample - 1) / sample, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            if (split) {
                Paint white = new Paint();
                white.setColor(Color.WHITE);
                new Canvas(bitmap).drawRect(Math.max(0, (510 - region.left) / (float) sample),
                    0, bitmap.getWidth(), bitmap.getHeight(), white);
            }
            if (splitHorizontal) {
                Paint white = new Paint();
                white.setColor(Color.WHITE);
                new Canvas(bitmap).drawRect(0, Math.max(0, (2040 - region.top) / (float) sample),
                    bitmap.getWidth(), bitmap.getHeight(), white);
            }
            return bitmap;
        }

        @Override public void close() { closes++; }
    }
}
