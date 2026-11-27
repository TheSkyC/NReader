// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
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
    public void tapDuringSettleKeepsBothPagesInPlaceAndResumesTheOriginalTransition() {
        AtomicInteger clicks = new AtomicInteger();
        View next = layout.getChildAt(1);
        child.setOnClickListener(view -> clicks.incrementAndGet());
        next.setOnClickListener(view -> clicks.incrementAndGet());
        dispatch(0, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(10, MotionEvent.ACTION_MOVE, 250, 200);
        dispatch(20, MotionEvent.ACTION_UP, 220, 200);
        seekAnimation(0.05f);
        float offset = child.getTranslationX();
        float nextOffset = next.getTranslationX();
        float intermediateIndicator = indicator;
        assertTrue(intermediateIndicator < 1f / 3f);

        dispatch(100, MotionEvent.ACTION_DOWN, 200, 200);
        assertEquals(offset, child.getTranslationX(), 0f);
        assertEquals(nextOffset, next.getTranslationX(), 0f);
        assertEquals(View.VISIBLE, next.getVisibility());
        assertEquals(intermediateIndicator, indicator, 0f);

        dispatch(150, MotionEvent.ACTION_UP, 201, 200);
        assertEquals(offset, child.getTranslationX(), 0f);
        assertEquals(0, selected);
        finishAnimation();
        assertEquals(1, selected);
        assertEquals(1f, indicator, 0f);
        assertEquals(0, clicks.get());
    }

    @Test
    public void dragDuringSettleFollowsFingerFromTheIntermediatePosition() {
        startSettling(100);
        float offset = child.getTranslationX();

        dispatch(1100, MotionEvent.ACTION_DOWN, 150, 200);
        dispatch(1500, MotionEvent.ACTION_MOVE, 330, 200);
        assertEquals(offset + 180 * density, child.getTranslationX(), 1f);
        assertEquals(-child.getTranslationX() / layout.getWidth(), indicator, 0.01f);
        dispatch(2000, MotionEvent.ACTION_UP, 330, 200);
        finishAnimation();
        assertEquals(0, selected);
        assertEquals(0f, indicator, 0f);
    }

    @Test
    public void dragDuringReturnToOriginalPageCanSettleOnTheNextPage() {
        startSettling(200);
        float offset = child.getTranslationX();

        dispatch(1100, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(1500, MotionEvent.ACTION_MOVE, 150, 200);
        assertEquals(offset - 150 * density, child.getTranslationX(), 1f);
        dispatch(2000, MotionEvent.ACTION_UP, 150, 200);
        finishAnimation();
        assertEquals(1, selected);
        assertEquals(1f, indicator, 0f);
    }

    @Test
    public void diagonalDragDuringSettleKeepsTheTabsStillWhileTheChildScrolls() {
        startSettling(200);
        float offset = child.getTranslationX();
        int moves = child.moves;

        dispatch(1100, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(1500, MotionEvent.ACTION_MOVE, 140, 330);
        assertEquals(offset, child.getTranslationX(), 0f);
        assertEquals(moves + 1, child.moves);
        finishAnimation();
        dispatch(1600, MotionEvent.ACTION_MOVE, 90, 300);
        assertEquals(offset, child.getTranslationX(), 0f);
        assertEquals(moves + 2, child.moves);
        dispatch(2000, MotionEvent.ACTION_UP, 90, 300);
        finishAnimation();
        assertEquals(0, selected);
        assertEquals(0f, indicator, 0f);
    }

    @Test
    public void horizontalDragDuringSettleChecksTheVisiblePageForNestedScrolling() {
        startSettling(200);
        child.canScroll = true;
        float offset = child.getTranslationX();

        dispatch(1100, MotionEvent.ACTION_DOWN, 360, 200);
        dispatch(1500, MotionEvent.ACTION_MOVE, 190, 200);
        assertEquals(offset - 170 * density, child.getTranslationX(), 1f);
        dispatch(2000, MotionEvent.ACTION_UP, 190, 200);
        finishAnimation();
        assertEquals(1, selected);
    }

    @Test
    public void systemEdgeTouchLetsTheCurrentAnimationFinish() {
        startSettling(100);
        float offset = child.getTranslationX();

        dispatch(1100, MotionEvent.ACTION_DOWN, 10, 200);
        assertEquals(offset, child.getTranslationX(), 0f);
        finishAnimation();
        dispatch(1200, MotionEvent.ACTION_CANCEL, 10, 200);
        assertEquals(1, selected);
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

    private void startSettling(float toX) {
        dispatch(0, MotionEvent.ACTION_DOWN, 300, 200);
        dispatch(500, MotionEvent.ACTION_MOVE, toX, 200);
        dispatch(1000, MotionEvent.ACTION_UP, toX, 200);
        seekAnimation(0.25f);
    }

    private void seekAnimation(float fraction) {
        ValueAnimator animator = ReflectionHelpers.getField(layout, "animator");
        animator.setCurrentFraction(fraction);
    }

    private static class ScrollableChild extends View {
        boolean canScroll;
        int moves;

        ScrollableChild(Context context) {
            super(context);
            setClickable(true);
        }

        @Override public boolean canScrollHorizontally(int direction) { return canScroll; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) moves++;
            return super.onTouchEvent(event);
        }
    }
}
