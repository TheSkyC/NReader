// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.DebugImageMask;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBitmapRegionDecoder;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = ContinuousTilePipelineTest.ClosingRegionDecoderShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContinuousTilePipelineTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void adapterAndGlideReleaseSnapshotOwnershipAcrossAppearanceAndLifecycleChanges() throws Exception {
        File sourceDirectory = folder.newFolder("pages");
        File source = new File(sourceDirectory, "1.png");
        Bitmap bitmap = Bitmap.createBitmap(600, 12000, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        bitmap.setPixel(200, 100, Color.BLACK);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Global.initFromShared(activity);
        boolean originalMask = DebugImageMask.isEnabled(activity);
        DebugImageMask.setEnabled(activity, false);
        ContinuousZoomViewport recycler = new ContinuousZoomViewport(activity, null);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        LocalGallery gallery = new LocalGallery(sourceDirectory, true);
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {});
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        try {
            await(recycler, () -> holder(recycler) != null && image(recycler).getDrawable() != null
                && holder(recycler).status.getVisibility() == View.GONE);
            adapter.setTilesActive(true);
            await(recycler, () -> image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            ContinuousPageAdapter.Holder holder = holder(recycler);
            ContinuousTileSnapshotSession session = ReflectionHelpers.getField(holder, "tileSession");
            assertNotNull(session);
            File snapshot = session.file();
            assertTrue(snapshot.isFile());
            assertEquals(600, image(recycler).getDrawable().getIntrinsicWidth());
            assertEquals(12000, image(recycler).getDrawable().getIntrinsicHeight());
            Bitmap frame = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888);
            try {
                await(recycler, () -> {
                    recycler.draw(new Canvas(frame));
                    ContinuousTileCache cache = ReflectionHelpers.getField(adapter, "tileCache");
                    return ((Long) ReflectionHelpers.getField(cache, "bytes")) > 0;
                });
            } finally {
                frame.recycle();
            }
            adapter.setTilesActive(false);
            assertFalse(image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            await(recycler, () -> !snapshot.exists());
            ContinuousTileCache cache = ReflectionHelpers.getField(adapter, "tileCache");
            assertEquals(0L, (long) ReflectionHelpers.<Long>getField(cache, "bytes"));
            DelayingSnapshotLoader delayed = new DelayingSnapshotLoader();
            Glide.get(activity).getRegistry().prepend(ContinuousTileSnapshotSession.class, InputStream.class, delayed);
            adapter.setTilesActive(true);
            try {
                await(recycler, () -> delayed.started.getCount() == 0 && holder(recycler).tileSession != null);
                File canceledSnapshot = holder(recycler).tileSession.file();
                adapter.setTilesActive(false);
                delayed.release.countDown();
                await(recycler, () -> !canceledSnapshot.exists());
                assertNull(holder(recycler).tileSession);
                assertFalse(image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            } finally {
                delayed.release.countDown();
            }
            adapter.setTilesActive(true);
            await(recycler, () -> image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            for (int round = 0; round < 6; round++) {
                File detachedSnapshot = holder(recycler).tileSession.file();
                recycler.setAdapter(null);
                recycler.setAdapter(adapter);
                await(recycler, () -> !detachedSnapshot.exists()
                    && image(recycler).getDrawable() instanceof ContinuousTileDrawable);
                adapter.setTilesActive(false);
                adapter.setTilesActive(true);
                adapter.notifyItemChanged(0);
                await(recycler, () -> image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            }
            adapter.setTilesActive(false);
            await(recycler, () -> {
                File[] remaining = snapshot.getParentFile().listFiles((directory, name) -> name.endsWith(".snapshot"));
                return remaining != null && remaining.length == 0;
            });
            adapter.setTilesActive(true);
            await(recycler, () -> image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            adapter.setAutoCrop(true);
            await(recycler, () -> holder(recycler).status.getVisibility() == View.GONE && image(recycler).getDrawable() != null);
            assertFalse(image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            assertEquals(source.getCanonicalFile(), new File(adapter.loadedSource(holder(recycler)).toString()).getCanonicalFile());
            adapter.setTilesActive(false);
            adapter.setAutoCrop(false);
            await(recycler, () -> holder(recycler).status.getVisibility() == View.GONE && image(recycler).getDrawable() != null);
            DebugImageMask.setEnabled(activity, true);
            adapter.setTilesActive(true);
            assertNull(holder(recycler).tileRequest);
            assertFalse(image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            adapter.setTilesActive(false);
            DebugImageMask.setEnabled(activity, false);
            adapter.setTilesActive(true);
            await(recycler, () -> image(recycler).getDrawable() instanceof ContinuousTileDrawable);
            assertTrue(adapter.rotate(holder(recycler)));
            await(recycler, () -> holder(recycler).status.getVisibility() == View.GONE && image(recycler).getDrawable() != null);
            assertNull(holder(recycler).tileRequest);
            assertFalse(image(recycler).getDrawable() instanceof ContinuousTileDrawable);
        } finally {
            adapter.setTilesActive(false);
            recycler.setAdapter(null);
            DebugImageMask.setEnabled(activity, originalMask);
            activity.finish();
        }
    }

    private static ContinuousPageAdapter.Holder holder(ContinuousZoomViewport recycler) {
        return (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(0);
    }

    private static ImageView image(ContinuousZoomViewport recycler) {
        return holder(recycler).itemView.findViewById(R.id.continuous_page_image);
    }

    private static void await(ContinuousZoomViewport recycler, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            recycler.layout(0, 0, 600, 500);
            recycler.getViewTreeObserver().dispatchOnPreDraw();
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Image pipeline did not settle before timeout");
    }

    // Robolectric 4.16.1's filename overload leaks its stream on Windows. Region
    // pixels and sampling still use its shadow and require separate device checks.
    @Implements(BitmapRegionDecoder.class)
    public static class ClosingRegionDecoderShadow extends ShadowBitmapRegionDecoder {
        @Implementation
        protected static BitmapRegionDecoder newInstance(String path, boolean shareable) throws IOException {
            try (FileInputStream input = new FileInputStream(path)) {
                return newInstance(input, shareable);
            }
        }
    }

    private static final class DelayingSnapshotLoader implements ModelLoaderFactory<ContinuousTileSnapshotSession, InputStream> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public ModelLoader<ContinuousTileSnapshotSession, InputStream> build(MultiModelLoaderFactory factory) {
            ContinuousTileSnapshotLoader delegate = new ContinuousTileSnapshotLoader();
            return new ModelLoader<>() {
                @Override public boolean handles(ContinuousTileSnapshotSession model) { return true; }

                @Override public LoadData<InputStream> buildLoadData(ContinuousTileSnapshotSession model, int width, int height,
                                                                    Options options) {
                    LoadData<InputStream> data = delegate.buildLoadData(model, width, height, options);
                    assertNotNull(data);
                    return new LoadData<>(data.sourceKey, new DataFetcher<>() {
                        @Override public void loadData(Priority priority, DataCallback<? super InputStream> callback) {
                            started.countDown();
                            try {
                                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Snapshot gate timed out");
                                data.fetcher.loadData(priority, callback);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                callback.onLoadFailed(new IOException("Snapshot gate interrupted", error));
                            } catch (IOException error) {
                                callback.onLoadFailed(error);
                            }
                        }
                        @Override public void cleanup() { data.fetcher.cleanup(); }
                        @Override public void cancel() { data.fetcher.cancel(); release.countDown(); }
                        @Override public Class<InputStream> getDataClass() { return InputStream.class; }
                        @Override public DataSource getDataSource() { return DataSource.LOCAL; }
                    });
                }
            };
        }

        @Override public void teardown() { release.countDown(); }
    }
}
