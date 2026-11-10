// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.content.Context;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GalleryTabSwipeLayoutTest {
    private GalleryTabSwipeLayout layout;
    private ScrollableChild child;
    private int selected;
    private float indicator;
    private float density;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        density = context.getResources().getDisplayMetrics().density;
        layout = new GalleryTabSwipeLayout(context, null);
        child = new ScrollableChild(context);
        layout.addView(child, new ViewGroup.LayoutParams(-1, -1));
        for (int index = 1; index < 3; index++) {
            View page = new View(context);
            page.setClickable(true);
            layout.addView(page, new ViewGroup.LayoutParams(-1, -1));
        }
        layout.setCurrentPage(0);
        int width = Math.round(400 * density);
        int height = Math.round(600 * density);
        layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, width, height);
        layout.setOnPageSwipeListener(new GalleryTabSwipeLayout.OnPageSwipeListener() {
            @Override public void onPageScrolled(int position, float offset) { indicator = position + offset; }
            @Override public void onPageSelected(int position) { selected = position; }
        });
    }

    @Test
    public void followsFingerAndSettlesOnNextPage() {
        dispatch(0, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(500, MotionEvent.ACTION_MOVE, 100, 200);
        assertEquals(-200 * density, child.getTranslationX(), 1f);
        assertEquals(View.VISIBLE, layout.getChildAt(1).getVisibility());
        assertEquals(200 * density, layout.getChildAt(1).getTranslationX(), 1f);
        assertEquals(0.5f, indicator, 0.01f);
        dispatch(1000, MotionEvent.ACTION_UP, 100, 200);
        finishAnimation();
        assertEquals(1, selected);
        assertEquals(View.INVISIBLE, child.getVisibility());
        assertEquals(0f, layout.getChildAt(1).getTranslationX(), 0f);
        assertEquals(1f, indicator, 0f);
    }

    @Test
    public void shortSlowDragAndCancelReturnToOriginalPage() {
        dispatch(0, MotionEvent.ACTION_DOWN, 250, 200);
        dispatch(500, MotionEvent.ACTION_MOVE, 200, 200);
        dispatch(1000, MotionEvent.ACTION_UP, 200, 200);
        finishAnimation();
        assertEquals(0, selected);
        assertEquals(View.INVISIBLE, layout.getChildAt(1).getVisibility());
        assertEquals(0f, child.getTranslationX(), 0f);
        dispatch(2000, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(2500, MotionEvent.ACTION_MOVE, 100, 200);
        dispatch(2600, MotionEvent.ACTION_CANCEL, 100, 200);
        finishAnimation();
        assertEquals(0, selected);
        assertEquals(0f, indicator, 0f);
    }

    @Test
    public void rtlAndDirectionReversalRevealCorrectNeighbor() {
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        layout.setCurrentPage(1);
        selected = 1;
        dispatch(0, MotionEvent.ACTION_DOWN, 200, 200);
        dispatch(500, MotionEvent.ACTION_MOVE, 300, 200);
        assertEquals(View.VISIBLE, layout.getChildAt(2).getVisibility());
        dispatch(600, MotionEvent.ACTION_MOVE, 100, 200);
        assertEquals(View.INVISIBLE, layout.getChildAt(2).getVisibility());
        assertEquals(View.VISIBLE, layout.getChildAt(0).getVisibility());
        dispatch(1100, MotionEvent.ACTION_UP, 30, 200);
        finishAnimation();
        assertEquals(0, selected);
    }

    @Test
    public void preservesNestedHorizontalScrollingSystemEdgeAndVerticalGestures() {
        child.canScroll = true;
        gesture(300, 200, 100, 200);
        child.canScroll = false;
        gesture(10, 200, 180, 200);
        gesture(200, 100, 215, 250);
        assertEquals(0, selected);
        assertEquals(0f, child.getTranslationX(), 0f);
    }

    @Test
    public void fastFlingChangesPageAndOuterEdgeReturns() {
        dispatch(0, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(10, MotionEvent.ACTION_MOVE, 250, 200);
        dispatch(20, MotionEvent.ACTION_UP, 220, 200);
        finishAnimation();
        assertEquals(1, selected);
        layout.setCurrentPage(0);
        selected = 0;
        gesture(100, 200, 300, 200);
        assertEquals(0, selected);
        assertEquals(0f, child.getTranslationX(), 0f);
    }

    private void gesture(float fromX, float fromY, float toX, float toY) {
        dispatch(0, MotionEvent.ACTION_DOWN, fromX, fromY);
        dispatch(500, MotionEvent.ACTION_MOVE, toX, toY);
        dispatch(1000, MotionEvent.ACTION_UP, toX, toY);
        finishAnimation();
    }

    private void dispatch(long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, time, action, x * density, y * density, 0);
        layout.dispatchTouchEvent(event);
        event.recycle();
    }

    private void finishAnimation() {
        ValueAnimator animator = ReflectionHelpers.getField(layout, "animator");
        if (animator != null) animator.end();
    }

    private static class ScrollableChild extends View {
        boolean canScroll;

        ScrollableChild(Context context) {
            super(context);
            setClickable(true);
        }

        @Override public boolean canScrollHorizontally(int direction) { return canScroll; }
    }
}
