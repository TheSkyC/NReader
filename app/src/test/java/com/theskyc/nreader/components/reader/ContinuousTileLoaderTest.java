// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileLoaderTest {
    @Test
    public void replacesObsoleteRequestAndClosesWithoutDeliveringOldCallbacks() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<ContinuousTileLoader> reference = new AtomicReference<>();
        Rect first = new Rect(0, 0, 8, 8);
        Rect latest = new Rect(0, 8, 8, 16);
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, ui, callbacks::incrementAndGet, failure -> { throw new AssertionError(failure); });
        reference.set(loader);
        source.onDecode = () -> {
            source.onDecode = null;
            reference.get().request(latest, 1);
        };
        loader.request(first, 1);
        loader.request(first, 1);
        assertEquals(1, worker.size());
        worker.runNext();
        assertEquals(2, source.regions.size());
        assertEquals(first, source.regions.get(0));
        assertEquals(latest, source.regions.get(1));
        assertTrue(source.bitmaps.get(0).isRecycled());
        assertNull(loader.get(first, 1));
        assertNotNull(loader.get(latest, 1));
        assertEquals(1, ui.size());
        loader.close();
        assertEquals(0, source.closeCount);
        ui.runNext();
        assertEquals(0, callbacks.get());
        worker.runNext();
        assertEquals(1, source.closeCount);
        assertNull(loader.get(latest, 1));
    }

    @Test
    public void closeDuringDecodeDiscardsResultAndReleasesSource() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        AtomicInteger callbacks = new AtomicInteger();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, ui, callbacks::incrementAndGet, failure -> callbacks.incrementAndGet());
        source.onDecode = loader::close;
        loader.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        assertTrue(source.bitmaps.get(0).isRecycled());
        assertEquals(1, source.closeCount);
        assertEquals(0, ui.size());
        assertEquals(0, callbacks.get());
    }

    @Test
    public void reportsFailureOnlyIfSessionIsStillInterested() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.fail = true;
        AtomicInteger failures = new AtomicInteger();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, ui, () -> { throw new AssertionError(); }, failure -> failures.incrementAndGet());
        loader.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        assertEquals(1, source.closeCount);
        ui.runNext();
        assertEquals(1, failures.get());
        loader.close();
        assertEquals(1, source.closeCount);

        FakeSource canceledSource = new FakeSource();
        canceledSource.fail = true;
        ContinuousTileLoader canceled = new ContinuousTileLoader(canceledSource, new ContinuousTileCache(4096, 4, 256),
            worker, ui, () -> { throw new AssertionError(); }, failure -> failures.incrementAndGet());
        canceled.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        canceled.close();
        ui.runNext();
        assertEquals(1, failures.get());
    }

    @Test
    public void rejectedExecutorsAndTooSmallCacheReleaseTheSource() {
        Executor rejected = command -> { throw new RejectedExecutionException(); };
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        AtomicInteger failures = new AtomicInteger();
        FakeSource source = new FakeSource();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            rejected, ui, () -> {}, failure -> failures.incrementAndGet());
        loader.request(new Rect(0, 0, 8, 8), 1);
        assertEquals(1, source.closeCount);
        ui.runNext();
        assertEquals(1, failures.get());

        FakeSource uncached = new FakeSource();
        ContinuousTileLoader tooSmall = new ContinuousTileLoader(uncached, new ContinuousTileCache(1, 4, 1),
            worker, ui, () -> {}, failure -> failures.incrementAndGet());
        tooSmall.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        assertTrue(uncached.bitmaps.get(0).isRecycled());
        assertEquals(1, uncached.closeCount);
        ui.runNext();
        assertEquals(2, failures.get());

        FakeSource noUi = new FakeSource();
        ContinuousTileLoader noDelivery = new ContinuousTileLoader(noUi, new ContinuousTileCache(4096, 4, 256),
            worker, rejected, () -> {}, failure -> { throw new AssertionError(failure); });
        noDelivery.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        assertEquals(1, noUi.closeCount);
        assertFalse(noUi.bitmaps.get(0).isRecycled());
        assertNull(noDelivery.get(new Rect(0, 0, 8, 8), 1));
    }

    @Test
    public void outOfMemoryDuringDecodeClosesSourceAndReportsFallback() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        source.outOfMemory = true;
        AtomicReference<Exception> failure = new AtomicReference<>();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, ui, () -> { throw new AssertionError(); }, failure::set);
        source.onDecode = () -> loader.request(new Rect(0, 8, 8, 16), 1);
        loader.request(new Rect(0, 0, 8, 8), 1);
        worker.runNext();
        assertEquals(1, source.closeCount);
        assertEquals(1, source.regions.size());
        ui.runNext();
        assertNotNull(failure.get());
        assertTrue(failure.get().getCause() instanceof OutOfMemoryError);
    }

    @Test
    public void temporaryLackOfCapacityDoesNotPermanentlyFailSession() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        FakeSource source = new FakeSource();
        ContinuousTileCache cache = new ContinuousTileCache(256, 1, 256);
        ContinuousTileCache.Session other = cache.newSession();
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, cache, worker, ui,
            updates::incrementAndGet, failure -> failures.incrementAndGet());
        Rect region = new Rect(0, 0, 8, 8);
        loader.request(region, 1);
        worker.runNext();
        assertEquals(0, failures.get());
        assertEquals(0, source.closeCount);
        assertTrue(source.bitmaps.get(0).isRecycled());
        cache.removeSession(other);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        while (!ui.queue.isEmpty()) ui.runNext();
        assertEquals(1, updates.get());
        loader.request(region, 1);
        worker.runNext();
        ui.runNext();
        assertNotNull(loader.get(region, 1));
        assertEquals(0, failures.get());
        loader.close();
        worker.runNext();
    }

    @Test
    public void closeDoesNotWaitForDecodeOnAnotherThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        FakeSource source = new FakeSource();
        source.onDecode = () -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Decode did not resume");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        source.onClose = closed::countDown;
        AtomicInteger callbacks = new AtomicInteger();
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, Runnable::run, callbacks::incrementAndGet, failure -> callbacks.incrementAndGet());
        try {
            loader.request(new Rect(0, 0, 8, 8), 1);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<?> close = closer.submit(loader::close);
            close.get(2, TimeUnit.SECONDS);
            assertEquals(0, source.closeCount);
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertEquals(1, source.closeCount);
            assertTrue(source.bitmaps.get(0).isRecycled());
            assertEquals(0, callbacks.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            closer.shutdownNow();
        }
    }

    @Test
    public void requestChangesDuringBackgroundDecodeDiscardOldTile() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        FakeSource source = new FakeSource();
        source.onDecode = () -> {
            source.onDecode = null;
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Decode did not resume");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        source.onClose = closed::countDown;
        Rect oldRegion = new Rect(0, 0, 8, 8);
        Rect newRegion = new Rect(0, 8, 8, 16);
        ContinuousTileLoader loader = new ContinuousTileLoader(source, new ContinuousTileCache(4096, 4, 256),
            worker, Runnable::run, ready::countDown, failure -> { throw new AssertionError(failure); });
        try {
            loader.request(oldRegion, 1);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            loader.request(newRegion, 1);
            release.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(2, source.regions.size());
            assertTrue(source.bitmaps.get(0).isRecycled());
            assertNull(loader.get(oldRegion, 1));
            assertNotNull(loader.get(newRegion, 1));
        } finally {
            release.countDown();
            loader.close();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            worker.shutdownNow();
        }
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        int size() { return queue.size(); }
        void runNext() { queue.remove().run(); }
    }

    private static final class FakeSource implements ContinuousTileLoader.Source {
        final List<Rect> regions = new ArrayList<>();
        final List<Bitmap> bitmaps = new ArrayList<>();
        Runnable onDecode;
        Runnable onClose;
        boolean fail;
        boolean outOfMemory;
        int closeCount;

        @NonNull @Override public Bitmap decode(@NonNull Rect region, int sample) throws IOException {
            regions.add(new Rect(region));
            if (onDecode != null) onDecode.run();
            if (fail) throw new IOException("Synthetic decode failure");
            if (outOfMemory) throw new OutOfMemoryError("Synthetic decode failure");
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmaps.add(bitmap);
            return bitmap;
        }

        @Override public void close() {
            closeCount++;
            if (onClose != null) onClose.run();
        }
    }
}
