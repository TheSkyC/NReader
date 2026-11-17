// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

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

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.components.classes.ReadingPosition;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ContinuousReaderNavigationTest {
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
        navigation.dispose();
    }

    private void layoutRecycler(RecyclerView recycler) {
        recycler.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 600, 500);
    }
}
