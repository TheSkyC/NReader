// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.BaseRequestOptions;
import com.bumptech.glide.request.Request;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.settings.Global;

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
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousPageAdapterTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingPageShowsRetryAndDoesNotBlockOtherPageSelection() throws Exception {
        File folder = temporaryFolder.newFolder();
        assertTrue(new File(folder, "1.png").createNewFile());
        assertTrue(new File(folder, "3.png").createNewFile());
        LocalGallery gallery = new LocalGallery(folder, true);
        Global.initFromShared(RuntimeEnvironment.getApplication());
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
