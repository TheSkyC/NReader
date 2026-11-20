// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import com.bumptech.glide.Priority;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.request.FutureTarget;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTileSnapshotSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void unsupportedAndShortImagesDoNotReserveSnapshotSpace() throws Exception {
        File shortPng = createPng();
        File jpeg = folder.newFile("long.jpg");
        Bitmap bitmap = Bitmap.createBitmap(100, 6000, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(jpeg)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output));
        } finally {
            bitmap.recycle();
        }
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("preflight"), 65536, 65536);
             ContinuousTileSnapshotStore.Lease occupied = store.create(shortPng, () -> false)) {
            assertNull(ContinuousTileSnapshotSession.open(store, shortPng, () -> false, 4096,
                error -> { throw new AssertionError(error); }));
            assertNull(ContinuousTileSnapshotSession.open(store, jpeg, () -> false, 4096,
                error -> { throw new AssertionError(error); }));
            assertTrue(occupied.file().isFile());
        }
    }

    @Test
    public void realGlideRequestReadsSnapshotAfterSourceReplacement() throws Exception {
        File source = createPng();
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("cache"), 65536, 65536)) {
            ContinuousTileSnapshotSession session = new ContinuousTileSnapshotSession(store.create(source, () -> false),
                new FakeSource(), 8, 64, error -> { throw new AssertionError(error); });
            File snapshot = session.file();
            Files.write(source.toPath(), new byte[] {1, 2, 3});
            Glide.get(org.robolectric.RuntimeEnvironment.getApplication()).getRegistry().prepend(
                ContinuousTileSnapshotSession.class, InputStream.class, new ContinuousTileSnapshotLoader.Factory());
            RequestManager manager = Glide.with(org.robolectric.RuntimeEnvironment.getApplication());
            FutureTarget<Bitmap> target = manager.asBitmap().load(session).diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true).submit(8, 64);
            ExecutorService waiter = Executors.newSingleThreadExecutor();
            try {
                Bitmap decoded = waiter.submit(() -> target.get(15, TimeUnit.SECONDS)).get(20, TimeUnit.SECONDS);
                assertNotNull(decoded);
                assertTrue(snapshot.isFile());
                assertTrue(decoded.getWidth() > 0);
            } finally {
                manager.clear(target);
                waiter.shutdownNow();
                session.close();
            }
            assertFalse(snapshot.exists());
        }
    }

    @Test
    public void glideFetcherKeepsSnapshotUntilCleanupAndRejectsLateOpen() throws Exception {
        File source = createPng();
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("cache"), 65536, 65536)) {
            ContinuousTileSnapshotSession session = new ContinuousTileSnapshotSession(store.create(source, () -> false),
                new FakeSource(), 8, 64, error -> { throw new AssertionError(error); });
            File snapshot = session.file();
            ContinuousTileSnapshotLoader loader = new ContinuousTileSnapshotLoader();
            ModelLoader.LoadData<InputStream> first = loader.buildLoadData(session, 8, 64, new Options());
            ModelLoader.LoadData<InputStream> late = loader.buildLoadData(session, 8, 64, new Options());
            AtomicReference<InputStream> input = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();
            DataFetcher.DataCallback<InputStream> callback = new DataFetcher.DataCallback<InputStream>() {
                @Override public void onDataReady(InputStream data) { input.set(data); }
                @Override public void onLoadFailed(Exception failure) { error.set(failure); }
            };
            first.fetcher.loadData(Priority.NORMAL, callback);
            assertNotNull(input.get());
            session.close();
            assertTrue(snapshot.isFile());
            late.fetcher.loadData(Priority.NORMAL, callback);
            assertNotNull(error.get());
            late.fetcher.cleanup();
            first.fetcher.cleanup();
            assertFalse(snapshot.exists());
        }
    }

    @Test
    public void activeFallbackStreamKeepsSnapshotAfterBothOwnersRelease() throws Exception {
        File source = createPng();
        byte[] original = Files.readAllBytes(source.toPath());
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("cache"), 65536, 65536)) {
            ContinuousTileSnapshotSession session = new ContinuousTileSnapshotSession(store.create(source, () -> false),
                new FakeSource(), 8, 64, error -> { throw new AssertionError(error); });
            File snapshot = session.file();
            InputStream input = session.openFallbackStream();
            ContinuousTileLoader.Source tiles = session.takeSource();
            session.close();
            tiles.close();
            assertTrue(snapshot.isFile());
            assertThrows(IOException.class, session::openFallbackStream);
            assertArrayEquals(original, input.readAllBytes());
            input.close();
            input.close();
            assertFalse(snapshot.exists());
        }
    }

    @Test
    public void snapshotSurvivesUntilBothConsumersReleaseInEitherOrder() throws Exception {
        File source = createPng();
        byte[] original = Files.readAllBytes(source.toPath());
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("cache"), 65536, 65536)) {
            for (boolean releaseFallbackFirst : new boolean[] {true, false}) {
                Files.write(source.toPath(), original);
                ContinuousTileSnapshotSession session = new ContinuousTileSnapshotSession(store.create(source, () -> false),
                    new FakeSource(), 8, 64, error -> { throw new AssertionError(error); });
                assertNotNull(session);
                File snapshot = session.file();
                ContinuousTileLoader.Source tiles = session.takeSource();
                Files.write(source.toPath(), new byte[] {1, 2, 3});
                assertArrayEquals(original, Files.readAllBytes(snapshot.toPath()));
                if (releaseFallbackFirst) {
                    session.close();
                    assertTrue(snapshot.isFile());
                    Bitmap bitmap = tiles.decode(new Rect(0, 0, 8, 8), 1);
                    bitmap.recycle();
                    assertThrows(IOException.class, () -> store.create(source, () -> false));
                    tiles.close();
                } else {
                    tiles.close();
                    assertTrue(snapshot.isFile());
                    assertArrayEquals(original, Files.readAllBytes(session.file().toPath()));
                    assertThrows(IOException.class, () -> store.create(source, () -> false));
                    session.close();
                }
                assertFalse(snapshot.exists());
                tiles.close();
                session.close();
            }
        }
    }

    @Test
    public void abandonedAndIneligibleSessionsReleaseTheirReservations() throws Exception {
        File source = createPng();
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(folder.newFolder("cache"), 65536, 65536)) {
            ContinuousTileSnapshotSession session = new ContinuousTileSnapshotSession(store.create(source, () -> false),
                new FakeSource(), 8, 64, error -> { throw new AssertionError(error); });
            assertNotNull(session);
            File snapshot = session.file();
            session.close();
            assertFalse(snapshot.exists());
            Files.write(source.toPath(), new byte[] {1});
            assertNull(ContinuousTileSnapshotSession.open(store, source, () -> false, 10,
                error -> { throw new AssertionError(error); }));
            try (ContinuousTileSnapshotStore.Lease lease = store.create(source, () -> false)) {
                assertTrue(lease.file().isFile());
            }
        }
    }

    private File createPng() throws Exception {
        File source = folder.newFile("page.png");
        Bitmap bitmap = Bitmap.createBitmap(8, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return source;
    }

    private static final class FakeSource implements ContinuousTileLoader.Source {
        private boolean closed;
        @Override public Bitmap decode(Rect region, int sample) {
            if (closed) throw new IllegalStateException("Source is closed");
            return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        }
        @Override public void close() { closed = true; }
    }
}
