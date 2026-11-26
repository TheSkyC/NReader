// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ImageView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.BaseRequestOptions;
import com.bumptech.glide.request.Request;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.components.classes.ReadingPosition;
import com.theskyc.nreader.files.PageFile;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.network.NetworkUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousPageAdapterTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void changingPageAspectRatioKeepsContentAtMidPage() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        Bitmap bitmap = Bitmap.createBitmap(205, 4096, Bitmap.Config.ARGB_8888);
        BitmapDrawable fallback = new BitmapDrawable(activity.getResources(), bitmap);
        fallback.setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView).setImageDrawable(fallback);
            }
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(recycler);
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        recycler.scrollBy(0, 10000);
        ImageView image = (ImageView) layout.findViewByPosition(0);
        assertNotNull(image);
        float before = -image.getTop() / (float) image.getHeight();
        ContinuousTileLoader.Source source = new ContinuousTileLoader.Source() {
            @Override public Bitmap decode(Rect region, int sample) { throw new AssertionError(); }
            @Override public void close() {}
        };
        ContinuousTileDrawable tiles = new ContinuousTileDrawable(fallback, 300, 6000, 1, source,
            new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES),
            Runnable::run, Runnable::run, error -> { throw new AssertionError(error); });
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(tiles));
        AtomicInteger otherChange = new AtomicInteger();
        View detached = new View(activity);
        detached.setLayoutParams(new RecyclerView.LayoutParams(1, 1));
        navigation.changeImageKeepingPosition(detached, otherChange::incrementAndGet);
        assertEquals(1, otherChange.get());
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertTrue(recycler.getViewTreeObserver().dispatchOnPreDraw());
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        float after = -image.getTop() / (float) image.getHeight();
        assertEquals(before, after, 0.0001f);

        image.setImageDrawable(fallback);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(tiles));
        recycler.scrollBy(0, 100);
        int topAfterUserScroll = image.getTop();
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertFalse(recycler.getViewTreeObserver().dispatchOnPreDraw());
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertEquals(topAfterUserScroll, image.getTop());

        image.setImageDrawable(fallback);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(tiles));
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertTrue(recycler.getViewTreeObserver().dispatchOnPreDraw());
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500, 500, 0);
        recycler.onInterceptTouchEvent(down);
        down.recycle();
        recycler.scrollBy(0, 100);
        int topAfterTouchScroll = image.getTop();
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertEquals(topAfterTouchScroll, image.getTop());

        MotionEvent cancel = MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 500, 500, 0);
        recycler.onInterceptTouchEvent(cancel);
        cancel.recycle();
        image.setImageDrawable(fallback);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        MotionEvent heldDown = MotionEvent.obtain(2, 2, MotionEvent.ACTION_DOWN, 500, 500, 0);
        recycler.onInterceptTouchEvent(heldDown);
        heldDown.recycle();
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(tiles));
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        int topWhileHeld = image.getTop();
        assertFalse(recycler.getViewTreeObserver().dispatchOnPreDraw());
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertEquals(topWhileHeld, image.getTop());
        cancel = MotionEvent.obtain(2, 3, MotionEvent.ACTION_CANCEL, 500, 500, 0);
        recycler.onInterceptTouchEvent(cancel);
        cancel.recycle();
        navigation.dispose();
        tiles.close();
        recycler.setAdapter(null);
        bitmap.recycle();
    }

    @Test
    public void sourceAspectRatioChangesMeasuredPageHeightFromRoundedFallback() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        Bitmap bitmap = Bitmap.createBitmap(205, 4096, Bitmap.Config.ARGB_8888);
        BitmapDrawable fallback = new BitmapDrawable(activity.getResources(), bitmap);
        fallback.setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        image.setImageDrawable(fallback);
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        image.measure(width, height);
        int fallbackHeight = image.getMeasuredHeight();
        ContinuousTileLoader.Source source = new ContinuousTileLoader.Source() {
            @Override public Bitmap decode(Rect region, int sample) { throw new AssertionError(); }
            @Override public void close() {}
        };
        ContinuousTileDrawable tiles = new ContinuousTileDrawable(fallback, 300, 6000, 1, source,
            new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES),
            Runnable::run, Runnable::run, error -> { throw new AssertionError(error); });
        image.setImageDrawable(tiles);
        image.measure(width, height);
        assertEquals(21578, fallbackHeight);
        assertEquals(21600, image.getMeasuredHeight());
        tiles.close();
        bitmap.recycle();
    }

    @Test
    public void stoppingTilesKeepsVisiblePositionWhenFallbackAspectRatioDiffers() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {});
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        ContinuousPageAdapter.Holder holder = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        Bitmap bitmap = Bitmap.createBitmap(205, 4096, Bitmap.Config.ARGB_8888);
        BitmapDrawable fallback = new BitmapDrawable(activity.getResources(), bitmap);
        fallback.setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        ContinuousTileLoader.Source source = new ContinuousTileLoader.Source() {
            @Override public Bitmap decode(Rect region, int sample) { throw new AssertionError(); }
            @Override public void close() {}
        };
        ContinuousTileDrawable tiles = new ContinuousTileDrawable(fallback, 300, 6000, 1, source,
            new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES),
            Runnable::run, Runnable::run, error -> { throw new AssertionError(error); });
        holder.showImage();
        holder.tileDrawable = tiles;
        holder.tileFallback = fallback;
        holder.image.setImageDrawable(tiles);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        recycler.scrollBy(0, 10000);
        float before = -(holder.itemView.getTop() + holder.image.getTop()) / (float) holder.image.getHeight();
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        adapter.setImageTransition(navigation::changeImageKeepingPosition, navigation::cancelImageTransition);
        adapter.setTilesActive(true);
        adapter.setTilesActive(false);
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        float after = -(holder.itemView.getTop() + holder.image.getTop()) / (float) holder.image.getHeight();
        assertEquals(before, after, 0.0001f);
        assertSame(fallback, holder.image.getDrawable());

        ContinuousTileDrawable rebound = new ContinuousTileDrawable(fallback, 300, 6000, 1, source,
            new ContinuousTileCache(2 * 1024 * 1024, 1, ContinuousTileDecoder.MAX_TILE_BYTES),
            Runnable::run, Runnable::run, error -> { throw new AssertionError(error); });
        navigation.changeImageKeepingPosition(holder.itemView, () -> holder.image.setImageDrawable(rebound));
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        adapter.onBindViewHolder(holder, 0);
        holder.image.setImageDrawable(rebound);
        holder.showImage();
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        int topAfterRebind = holder.itemView.getTop();
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        recycler.measure(width, height);
        recycler.layout(0, 0, 1080, 1000);
        assertEquals(topAfterRebind, holder.itemView.getTop());
        navigation.dispose();
        recycler.setAdapter(null);
        rebound.close();
        bitmap.recycle();
    }

    @Test
    public void stoppingTilesRestoresGlideDrawableAndReleasesSession() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {});
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        ContinuousPageAdapter.Holder holder = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        AtomicInteger closes = new AtomicInteger();
        ContinuousTileLoader.Source source = new ContinuousTileLoader.Source() {
            @Override public Bitmap decode(Rect region, int sample) { return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        ColorDrawable fallback = new ColorDrawable(Color.RED);
        ContinuousTileDrawable drawable = new ContinuousTileDrawable(fallback, 600, 12000, 4, source,
            new ContinuousTileCache(8 * 1024 * 1024, 4, ContinuousTileDecoder.MAX_TILE_BYTES),
            Runnable::run, Runnable::run, error -> { throw new AssertionError(error); });
        holder.tileDrawable = drawable;
        holder.image.setImageDrawable(drawable);
        adapter.setTilesActive(true);
        adapter.setTilesActive(false);
        assertNull(holder.tileDrawable);
        assertSame(fallback, holder.image.getDrawable());
        assertSame(holder.image, fallback.getCallback());
        assertEquals(1, closes.get());
        recycler.setAdapter(null);
    }

    @Test
    public void missingPageShowsRetryAndDoesNotBlockOtherPageSelection() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "3.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        NetworkUtil.setType(NetworkUtil.ConnectionType.UNKNOWN);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        AtomicInteger tapped = new AtomicInteger(-1);
        AtomicReference<Float> horizontal = new AtomicReference<>();
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {
            tapped.set(page);
            horizontal.set(x);
        }, Runnable::run));
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.ViewHolder first = recycler.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder missing = recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(first);
        assertNotNull(missing);
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_error).getVisibility());
        assertEquals(activity.getString(R.string.reader_page_load_failed),
            ((android.widget.TextView) missing.itemView.findViewById(R.id.continuous_page_error)).getText().toString());
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_retry).getVisibility());
        missing.itemView.findViewById(R.id.continuous_page_retry).performClick();
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_loading).getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(View.VISIBLE, missing.itemView.findViewById(R.id.continuous_page_error).getVisibility());
        first.itemView.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0, tapped.get());
        assertEquals(0.5f, horizontal.get(), 0.001f);
        float leftEdge = first.itemView.getWidth() * 0.05f;
        MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, leftEdge, 10, 0);
        MotionEvent up = MotionEvent.obtain(100, 120, MotionEvent.ACTION_UP, leftEdge, 10, 0);
        first.itemView.dispatchTouchEvent(down);
        first.itemView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0.05f, horizontal.get(), 0.001f);
        first.itemView.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0.5f, horizontal.get(), 0.001f);
        float rightEdge = first.itemView.getWidth() * 0.95f;
        down = MotionEvent.obtain(200, 200, MotionEvent.ACTION_DOWN, rightEdge, 10, 0);
        up = MotionEvent.obtain(200, 220, MotionEvent.ACTION_UP, rightEdge, 10, 0);
        first.itemView.dispatchTouchEvent(down);
        first.itemView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0.95f, horizontal.get(), 0.001f);
        down = MotionEvent.obtain(300, 300, MotionEvent.ACTION_DOWN, 30, 10, 0);
        MotionEvent cancel = MotionEvent.obtain(300, 320, MotionEvent.ACTION_CANCEL, 30, 10, 0);
        first.itemView.dispatchTouchEvent(down);
        first.itemView.dispatchTouchEvent(cancel);
        down.recycle();
        cancel.recycle();
        first.itemView.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0.5f, horizontal.get(), 0.001f);
        recycler.setAdapter(null);
    }

    @Test
    public void zoomPanCancelsPendingPageTap() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ContinuousZoomViewport viewport = new ContinuousZoomViewport(activity, null);
        viewport.setLayoutManager(new LinearLayoutManager(activity));
        AtomicInteger taps = new AtomicInteger();
        viewport.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> taps.incrementAndGet(), Runnable::run));
        activity.setContentView(viewport);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, 600, 500);
        ContinuousPageAdapter.Holder holder = (ContinuousPageAdapter.Holder) viewport.findViewHolderForAdapterPosition(0);
        assertNotNull(holder);
        holder.status.setVisibility(View.GONE);
        holder.image.setImageDrawable(new BitmapDrawable(activity.getResources(),
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
        viewport.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, 600, 500);
        viewport.toggleDoubleTapZoom(300, 200);
        ReaderZoomTestSupport.finish(viewport);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, 600, 500);
        assertTrue(viewport.currentScale() > 1f);
        holder.itemView.performClick();
        MotionEvent down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 300, 200, 0);
        MotionEvent move = MotionEvent.obtain(100, 120, MotionEvent.ACTION_MOVE, 450, 200, 0);
        viewport.dispatchTouchEvent(down);
        viewport.dispatchTouchEvent(move);
        down.recycle();
        move.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0, taps.get());
        viewport.setAdapter(null);
    }

    @Test
    public void scrollingCancelsPendingPageTap() throws Exception {
        File folder = temporaryFolder.newFolder();
        for (int page = 1; page <= 8; page++) assertTrue(new File(folder, page + ".png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        AtomicInteger taps = new AtomicInteger();
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> taps.incrementAndGet(), Runnable::run));
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 500);
        RecyclerView.ViewHolder first = recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(first);
        first.itemView.performClick();
        recycler.scrollBy(0, 40);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0, taps.get());
        first.itemView.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, taps.get());
        first.itemView.performClick();
        recycler.setAdapter(null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, taps.get());
    }

    @Test
    public void pageTapUsesViewportEdgesWhenZoomedAndPanned() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        AtomicReference<Float> horizontal = new AtomicReference<>();
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> horizontal.set(x), Runnable::run));
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.ViewHolder page = recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(page);
        for (int index = 0; index < 2; index++) {
            page.itemView.layout(0, 0, 1200, 1200);
            page.itemView.setTranslationX(-300);
            float x = 300f + recycler.getWidth() * (index == 0 ? 0.05f : 0.95f);
            long time = 1000L + index * 1000L;
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, 100, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, 100, 0);
            page.itemView.dispatchTouchEvent(down);
            page.itemView.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
                java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(index == 0 ? 0.05f : 0.95f, horizontal.get(), 0.001f);
        }
        recycler.setAdapter(null);
    }

    @Test
    public void accessibilityActionCanBeAddedAndRemovedFromBoundPages() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        AtomicInteger taps = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> taps.incrementAndGet(), Runnable::run);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.ViewHolder page = recycler.findViewHolderForAdapterPosition(0);
        assertNotNull(page);
        adapter.setOnAccessibilityToggleControls(actions::incrementAndGet);
        assertTrue(page.itemView.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(1, actions.get());
        assertEquals(0, taps.get());
        adapter.setOnAccessibilityToggleControls(null);
        assertTrue(page.itemView.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, actions.get());
        assertEquals(1, taps.get());
        recycler.setAdapter(null);
    }

    @Test
    public void cropChangeDuringRetryKeepsTheDirectoryRefresh() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "3.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> {}, queued::set);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        ContinuousPageAdapter.Holder missing = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(missing);
        File added = new File(folder, "2.png");
        assertTrue(added.createNewFile());
        missing.retry.performClick();
        assertNotNull(queued.get());
        adapter.setAutoCrop(true);
        queued.getAndSet(null).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(missing.source);
        assertEquals(added.getAbsolutePath(), ((PageFile) missing.source).getAbsolutePath());
        Request request = (Request) missing.image.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag);
        assertNotNull(request);
        BaseRequestOptions<?> options = ReflectionHelpers.getField(request, "requestOptions");
        Object transform = options.getTransformations().get(Bitmap.class);
        java.util.Collection<?> transforms = ReflectionHelpers.getField(transform, "transformations");
        assertTrue(transforms.stream().anyMatch(value -> value instanceof ReaderAutoCropTransformation));
        missing.retry.performClick();
        assertNotNull(queued.get());
        adapter.onBindViewHolder(missing, 1);
        Request reboundRequest = (Request) missing.image.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag);
        assertNotNull(reboundRequest);
        queued.getAndSet(null).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(added.getAbsolutePath(), ((PageFile) missing.source).getAbsolutePath());
        assertSame(reboundRequest, missing.image.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag));
        assertFalse(missing.retryPending);
        recycler.setAdapter(null);
    }

    @Test
    public void retryFindsNewLocalPageAndIgnoresResultsAfterRecycling() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "3.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor controlled = queued::set;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {}, controlled);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        ContinuousPageAdapter.Holder missing = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(missing);
        assertNull(missing.source);
        missing.retry.performClick();
        assertNotNull(queued.get());
        assertEquals(View.VISIBLE, missing.loading.getVisibility());
        File added = new File(folder, "2.png");
        assertTrue(added.createNewFile());
        queued.getAndSet(null).run();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(added.getAbsolutePath(), ((PageFile) missing.source).getAbsolutePath());
        adapter.onBindViewHolder(missing, 1);
        assertEquals(added.getAbsolutePath(), ((PageFile) missing.source).getAbsolutePath());

        missing.retry.performClick();
        assertNotNull(queued.get());
        recycler.setAdapter(null);
        queued.getAndSet(null).run();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNull(missing.source);
    }

    @Test
    public void rotationFollowsPageAndRejectsUnloadedOrRecycledHolders() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "2.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        ReaderPageRotations rotations = new ReaderPageRotations();
        ContinuousPageAdapter adapter = new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(),
            (page, x) -> {}, rotations);
        recycler.setAdapter(adapter);
        activity.setContentView(recycler);
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 1200);
        ContinuousPageAdapter.Holder first = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(0);
        ContinuousPageAdapter.Holder second = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(first);
        assertNotNull(second);
        Request originalRequest = (Request) first.image.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag);
        assertNotNull(originalRequest);
        BaseRequestOptions<?> originalOptions = ReflectionHelpers.getField(originalRequest, "requestOptions");
        int screenWidth = Math.max(1, Math.min(2048, activity.getResources().getDisplayMetrics().widthPixels));
        assertEquals(screenWidth, originalOptions.getOverrideWidth());
        assertEquals(4096, originalOptions.getOverrideHeight());
        assertFalse(adapter.rotate(first));
        first.image.setImageDrawable(new ColorDrawable(Color.RED));
        first.showImage();
        assertTrue(adapter.rotate(first));
        Request rotatedRequest = (Request) first.image.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag);
        assertNotNull(rotatedRequest);
        BaseRequestOptions<?> rotatedOptions = ReflectionHelpers.getField(rotatedRequest, "requestOptions");
        assertEquals(4096, rotatedOptions.getOverrideWidth());
        assertEquals(screenWidth, rotatedOptions.getOverrideHeight());
        assertEquals(270, rotations.angle(0));
        assertEquals(0, rotations.angle(1));
        assertFalse(adapter.rotate(first));
        adapter.onBindViewHolder(second, 1);
        assertEquals(0, rotations.angle(1));
        adapter.onBindViewHolder(first, 0);
        assertEquals(270, rotations.angle(0));
        recycler.setAdapter(null);
        assertFalse(adapter.rotate(first));
    }

    @Test
    public void shortFinalPageCanStaySelectedAndTracksViewportChanges() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "2.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new ContinuousReaderView(activity, null);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {}));
        activity.setContentView(recycler);
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        measureRecycler(recycler, 600);
        navigation.goToPosition(new ReadingPosition(2, 0f));
        measureRecycler(recycler, 600);
        ContinuousPageAdapter.Holder last = (ContinuousPageAdapter.Holder) recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(last);
        last.image.setImageDrawable(new BitmapDrawable(activity.getResources(),
            Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)));
        last.showImage();
        measureRecycler(recycler, 600);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        measureRecycler(recycler, 600);
        assertEquals(600, last.itemView.getHeight());
        assertEquals(0, last.itemView.getTop());
        MotionEvent tap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 100, 0);
        recycler.dispatchTouchEvent(tap);
        tap.recycle();
        measureRecycler(recycler, 600);
        assertEquals(1, navigation.currentPage());
        assertEquals(1, layout.findFirstVisibleItemPosition());

        measureRecycler(recycler, 900);
        recycler.requestLayout();
        measureRecycler(recycler, 900);
        assertEquals(900, last.itemView.getMinimumHeight());
        assertEquals(900, last.itemView.getHeight());
        navigation.dispose();
        recycler.setAdapter(null);
    }

    @Test
    public void pageGapIsAppliedBetweenPagesButNotAfterFinalPage() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "2.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setAdapter(new ContinuousPageAdapter(gallery, gallery.getGalleryFolder(), (page, x) -> {}));
        activity.setContentView(recycler);
        measureRecycler(recycler, 1200);
        RecyclerView.ViewHolder first = recycler.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder last = recycler.findViewHolderForAdapterPosition(1);
        assertNotNull(first);
        assertNotNull(last);
        assertEquals(Math.round(8 * activity.getResources().getDisplayMetrics().density),
            ((ViewGroup.MarginLayoutParams) first.itemView.getLayoutParams()).bottomMargin);
        assertEquals(0, ((ViewGroup.MarginLayoutParams) last.itemView.getLayoutParams()).bottomMargin);
        recycler.setAdapter(null);
    }

    private void measureRecycler(RecyclerView recycler, int height) {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, height);
    }
}
