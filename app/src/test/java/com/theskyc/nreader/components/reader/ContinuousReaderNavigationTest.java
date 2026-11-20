// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.R;
import com.theskyc.nreader.components.classes.ReadingPosition;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousReaderNavigationTest {
    @Test
    public void imageChangeAfterHorizontalPanRetainsVisibleFraction() {
        imageChangeAfterHorizontalPan(MotionEvent.ACTION_UP);
    }

    @Test
    public void imageChangeAfterCanceledHorizontalPanRetainsVisibleFraction() {
        imageChangeAfterHorizontalPan(MotionEvent.ACTION_CANCEL);
    }

    private void imageChangeAfterHorizontalPan(int terminalAction) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ContinuousZoomViewport recycler = new ContinuousZoomViewport(activity, null);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                FrameLayout item = new FrameLayout(parent.getContext());
                item.setClickable(true);
                item.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                item.addView(image, new FrameLayout.LayoutParams(-1, -2));
                View status = new View(parent.getContext());
                status.setId(R.id.continuous_page_status);
                status.setVisibility(View.GONE);
                item.addView(status);
                return new RecyclerView.ViewHolder(item) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView.findViewById(R.id.continuous_page_image))
                    .setImageDrawable(new SizedDrawable(600, 12000));
            }
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(recycler);
        layoutRecycler(recycler);
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        recycler.toggleDoubleTapZoom(300, 250);
        layoutRecycler(recycler);
        recycler.scrollBy(0, 4000);
        float offset = recycler.currentContentOffset();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, terminalAction};
        for (int index = 0; index < actions.length; index++) {
            MotionEvent event = MotionEvent.obtain(0, index * 100L, actions[index], index == 0 ? 500 : 50, 250, 0);
            recycler.dispatchTouchEvent(event);
            event.recycle();
        }
        assertTrue(recycler.currentContentOffset() < offset);
        View item = layout.findViewByPosition(0);
        assertNotNull(item);
        ImageView image = item.findViewById(R.id.continuous_page_image);
        float fraction = -item.getTop() / (float) image.getHeight();
        navigation.changeImageKeepingPosition(item, () -> image.setImageDrawable(new SizedDrawable(600, 14000)));
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        layoutRecycler(recycler);
        assertEquals(fraction, -item.getTop() / (float) image.getHeight(), 0.0001f);
        navigation.dispose();
        recycler.setAdapter(null);
    }

    @Test
    public void cancelAfterPreDrawKeepsCurrentPositionBeforeQueuedLayout() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView).setImageDrawable(new SizedDrawable(600, 1200));
            }
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(recycler);
        layoutRecycler(recycler);
        recycler.scrollBy(0, 300);
        ImageView image = (ImageView) layout.findViewByPosition(0);
        assertNotNull(image);
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(new SizedDrawable(600, 2400)));
        layoutRecycler(recycler);
        int topBeforeCancellation = image.getTop();
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        navigation.cancelImageTransition(image);
        layoutRecycler(recycler);
        assertEquals(topBeforeCancellation, image.getTop());
        navigation.dispose();
        recycler.setAdapter(null);
    }

    @Test
    public void replacementOutsideTransitionDiscardsStaleAnchor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView).setImageDrawable(new SizedDrawable(600, 1200));
            }
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(recycler);
        layoutRecycler(recycler);
        recycler.scrollBy(0, 300);
        ImageView image = (ImageView) layout.findViewByPosition(0);
        assertNotNull(image);
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(new SizedDrawable(600, 2400)));
        layoutRecycler(recycler);
        image.setImageDrawable(new SizedDrawable(600, 1800));
        layoutRecycler(recycler);
        int topAfterReplacement = image.getTop();
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        layoutRecycler(recycler);
        assertEquals(topAfterReplacement, image.getTop());
        navigation.dispose();
        recycler.setAdapter(null);
    }

    @Test
    public void repeatedImageChangesBeforeDrawingKeepOriginalPosition() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView).setImageDrawable(new SizedDrawable(600, 1200));
            }
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(recycler);
        layoutRecycler(recycler);
        recycler.scrollBy(0, 300);
        ImageView image = (ImageView) layout.findViewByPosition(0);
        assertNotNull(image);
        float originalFraction = -image.getTop() / (float) image.getHeight();
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(new SizedDrawable(600, 2400)));
        layoutRecycler(recycler);
        navigation.changeImageKeepingPosition(image, () -> image.setImageDrawable(new SizedDrawable(600, 1800)));
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnPreDraw();
        layoutRecycler(recycler);
        assertEquals(originalFraction, -image.getTop() / (float) image.getHeight(), 0.002f);
        navigation.dispose();
        recycler.setAdapter(null);
    }

    @Test
    public void adjacentAttachedPageUpdateDoesNotCancelCurrentPageCorrection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setAdjustViewBounds(true);
                image.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(image) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((ImageView) holder.itemView).setImageDrawable(new SizedDrawable(600, 400));
            }
            @Override public int getItemCount() { return 3; }
        });
        activity.setContentView(recycler);
        layoutRecycler(recycler);
        recycler.scrollBy(0, 150);
        ImageView first = (ImageView) layout.findViewByPosition(0);
        ImageView second = (ImageView) layout.findViewByPosition(1);
        assertNotNull(first);
        assertNotNull(second);
        float originalFraction = -first.getTop() / (float) first.getHeight();
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.changeImageKeepingPosition(first, () -> first.setImageDrawable(new SizedDrawable(600, 800)));
        navigation.changeImageKeepingPosition(second, () -> second.setImageDrawable(new SizedDrawable(600, 500)));
        layoutRecycler(recycler);
        assertTrue(recycler.getViewTreeObserver().dispatchOnPreDraw());
        layoutRecycler(recycler);
        assertEquals(originalFraction, -first.getTop() / (float) first.getHeight(), 0.002f);
        assertEquals(500, second.getDrawable().getIntrinsicHeight());
        navigation.dispose();
        recycler.setAdapter(null);
    }

    private static final class SizedDrawable extends ColorDrawable {
        private final int width;
        private final int height;
        SizedDrawable(int width, int height) { super(Color.BLUE); this.width = width; this.height = height; }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
    }

    @Test
    public void restoresAfterImageLayoutAndStopsFollowingAfterTouch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RecyclerView recycler = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity);
        AtomicInteger retries = new AtomicInteger();
        recycler.setLayoutManager(layout);
        recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                FrameLayout item = new FrameLayout(parent.getContext());
                item.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200));
                ImageView image = new ImageView(parent.getContext());
                image.setId(R.id.continuous_page_image);
                image.setImageDrawable(new ColorDrawable(Color.BLUE));
                item.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
                FrameLayout status = new FrameLayout(parent.getContext());
                status.setId(R.id.continuous_page_status);
                View retry = new View(parent.getContext());
                retry.setId(R.id.continuous_page_retry);
                retry.setOnClickListener(view -> retries.incrementAndGet());
                status.addView(retry, new FrameLayout.LayoutParams(100, 100));
                item.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
                return new RecyclerView.ViewHolder(item) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 3; }
        });
        activity.setContentView(recycler);
        ContinuousReaderNavigation navigation = new ContinuousReaderNavigation(recycler, layout, 0, ignored -> {});
        navigation.goToPosition(new ReadingPosition(2, 0.4f));
        layoutRecycler(recycler);
        View target = layout.findViewByPosition(1);
        assertNotNull(target);
        assertFalse(navigation.isPositionRestored());
        assertEquals(0.4f, navigation.currentPosition().offset, 0.001f);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertEquals(0, target.getTop());

        MotionEvent retryDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
        recycler.onInterceptTouchEvent(retryDown);
        retryDown.recycle();
        target.findViewById(R.id.continuous_page_retry).performClick();
        assertEquals(1, retries.get());
        assertEquals(0.4f, navigation.currentPosition().offset, 0.001f);

        MotionEvent pageDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 250, 0);
        recycler.onInterceptTouchEvent(pageDown);
        pageDown.recycle();
        assertNotNull(navigation.currentPosition());
        assertEquals(0.4f, navigation.currentPosition().offset, 0.001f);

        recycler.scrollBy(0, 200);
        assertEquals(-200, target.getTop());
        navigation.goToPosition(new ReadingPosition(2, 0.4f));
        MotionEvent immediateDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 250, 0);
        recycler.onInterceptTouchEvent(immediateDown);
        immediateDown.recycle();
        MotionEvent immediateDrag = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 300, 100, 0);
        recycler.onInterceptTouchEvent(immediateDrag);
        immediateDrag.recycle();
        layoutRecycler(recycler);
        assertEquals(-200, layout.findViewByPosition(1).getTop());
        MotionEvent immediateCancel = MotionEvent.obtain(0, 21, MotionEvent.ACTION_CANCEL, 300, 100, 0);
        recycler.onInterceptTouchEvent(immediateCancel);
        immediateCancel.recycle();
        navigation.goToPosition(new ReadingPosition(2, 0.4f));
        layoutRecycler(recycler);

        pageDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 250, 0);
        recycler.onInterceptTouchEvent(pageDown);
        pageDown.recycle();
        MotionEvent drag = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 300, 100, 0);
        recycler.onInterceptTouchEvent(drag);
        drag.recycle();
        assertNull(navigation.currentPosition());
        MotionEvent cancel = MotionEvent.obtain(0, 21, MotionEvent.ACTION_CANCEL, 300, 100, 0);
        recycler.onInterceptTouchEvent(cancel);
        cancel.recycle();
        navigation.goToPosition(new ReadingPosition(2, 0.4f));
        layoutRecycler(recycler);
        target = layout.findViewByPosition(1);
        assertNotNull(target);

        target.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertEquals(-480, layout.findViewByPosition(1).getTop());
        assertEquals(0.4f, navigation.currentPosition().offset, 0.001f);
        assertTrue(navigation.isPositionRestored());

        target = layout.findViewByPosition(1);
        target.getLayoutParams().height = 1600;
        target.requestLayout();
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertEquals(-640, layout.findViewByPosition(1).getTop());

        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 250, 0);
        recycler.dispatchTouchEvent(down);
        down.recycle();
        recycler.scrollBy(0, 120);
        assertEquals(0.475f, navigation.currentPosition().offset, 0.01f);

        navigation.goToPosition(new ReadingPosition(2, 0.475f));
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        recycler.scrollBy(0, 80);
        assertEquals(0.525f, navigation.currentPosition().offset, 0.01f);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertEquals(0.525f, navigation.currentPosition().offset, 0.01f);

        navigation.goToPosition(new ReadingPosition(3, 0.2f));
        recycler.scrollBy(0, 80);
        assertEquals(1, navigation.currentPage());
        float programmaticOffset = navigation.currentPosition().offset;
        layoutRecycler(recycler);
        assertEquals(1, layout.findFirstVisibleItemPosition());
        assertEquals(programmaticOffset, navigation.currentPosition().offset, 0.001f);

        navigation.goToPosition(new ReadingPosition(1, 0f));
        recycler.scrollBy(0, 900);
        assertEquals(2, navigation.currentPage());
        layoutRecycler(recycler);
        assertEquals(2, layout.findFirstVisibleItemPosition());
        assertEquals(2, navigation.currentPage());

        navigation.goToPosition(new ReadingPosition(2, 0.525f));
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);

        navigation.goToPosition(new ReadingPosition(3, 0.2f));
        MotionEvent secondDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300, 250, 0);
        recycler.dispatchTouchEvent(secondDown);
        secondDown.recycle();
        assertEquals(1, navigation.currentPage());
        recycler.scrollBy(0, 80);
        float interruptedOffset = navigation.currentPosition().offset;
        layoutRecycler(recycler);
        assertEquals(1, layout.findFirstVisibleItemPosition());
        assertEquals(1, navigation.currentPage());
        assertEquals(interruptedOffset, navigation.currentPosition().offset, 0.001f);
        navigation.goToPosition(new ReadingPosition(1, 0f));
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertEquals(0, layout.findFirstVisibleItemPosition());
        assertEquals(0, navigation.currentPage());
        navigation.goToPosition(new ReadingPosition(3, 0.99f));
        layoutRecycler(recycler);
        View last = layout.findViewByPosition(2);
        assertNotNull(last);
        last.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        recycler.getViewTreeObserver().dispatchOnGlobalLayout();
        layoutRecycler(recycler);
        assertTrue(navigation.isPositionRestored());
        navigation.dispose();
    }

    private void layoutRecycler(RecyclerView recycler) {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 500);
    }
}
