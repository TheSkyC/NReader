// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;

public class GalleryTabSwipeLayout extends FrameLayout {
    private final int touchSlop;
    private final int minimumVelocity;
    private final float edgeInset;
    private float startX;
    private float startY;
    private float offset;
    private boolean dragging;
    private boolean ignored;
    private int currentPage;
    private int targetPage = -1;
    private VelocityTracker velocityTracker;
    private ValueAnimator animator;
    private OnPageSwipeListener listener;

    public interface OnPageSwipeListener {
        void onPageScrolled(int position, float offset);
        void onPageSelected(int position);
    }

    public GalleryTabSwipeLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumVelocity = configuration.getScaledMinimumFlingVelocity();
        edgeInset = 24 * getResources().getDisplayMetrics().density;
        setClipChildren(true);
    }

    public void setOnPageSwipeListener(@Nullable OnPageSwipeListener listener) {
        this.listener = listener;
    }

    public void setCurrentPage(int page) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        currentPage = page;
        targetPage = -1;
        offset = 0;
        dragging = false;
        recycleVelocityTracker();
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            child.setTranslationX(0);
            child.setVisibility(index == page ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null) {
                    setCurrentPage(currentPage);
                    if (listener != null) listener.onPageScrolled(currentPage, 0);
                }
                startX = event.getX();
                startY = event.getY();
                ignored = startX < edgeInset || startX > getWidth() - edgeInset;
                dragging = false;
                recycleVelocityTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                ignored = true;
                recycleVelocityTracker();
                break;
            case MotionEvent.ACTION_MOVE:
                if (ignored || event.getPointerCount() != 1) break;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    ignored = true;
                } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    int direction = dx > 0 ? -1 : 1;
                    if (canScrollChild(this, startX, startY, direction)) {
                        ignored = true;
                    } else {
                        dragging = true;
                        updateDrag(dx);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        return false;
    }

    private static boolean canScrollChild(ViewGroup parent, float x, float y, int direction) {
        for (int index = parent.getChildCount() - 1; index >= 0; index--) {
            View child = parent.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE || x < child.getLeft() || x >= child.getRight()
                || y < child.getTop() || y >= child.getBottom()) continue;
            float childX = x - child.getLeft() + child.getScrollX();
            float childY = y - child.getTop() + child.getScrollY();
            if (child instanceof PhotoView && ((PhotoView) child).getScale() > 1f) return true;
            if (child instanceof ViewGroup && canScrollChild((ViewGroup) child, childX, childY, direction)) return true;
            if (child.canScrollHorizontally(direction)) return true;
        }
        return false;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility") // Tabs provide accessible click actions.
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return false;
        if (velocityTracker != null) velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateDrag(event.getX() - startX);
                break;
            case MotionEvent.ACTION_UP:
                updateDrag(event.getX() - startX);
                float velocity = 0;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velocity = velocityTracker.getXVelocity();
                }
                boolean fling = Math.abs(velocity) >= minimumVelocity && Math.signum(velocity) == Math.signum(offset);
                settle(targetPage >= 0 && (Math.abs(offset) >= getWidth() / 3f || fling));
                recycleVelocityTracker();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                settle(false);
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        return true;
    }

    private void updateDrag(float distance) {
        int direction = distance < 0 ? 1 : -1;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) direction = -direction;
        int next = distance == 0 ? -1 : currentPage + direction;
        if (next < 0 || next >= getChildCount()) next = -1;
        if (next != targetPage) {
            if (targetPage >= 0) {
                getChildAt(targetPage).setVisibility(View.INVISIBLE);
                getChildAt(targetPage).setTranslationX(0);
            }
            targetPage = next;
            if (targetPage >= 0) {
                float side = targetPage > currentPage ? 1 : -1;
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) side = -side;
                getChildAt(targetPage).setTranslationX(side * getWidth());
                getChildAt(targetPage).setVisibility(View.VISIBLE);
            }
        }
        float limit = targetPage < 0 ? getWidth() * 0.15f : getWidth();
        float adjusted = targetPage < 0 ? distance * 0.25f : distance;
        applyOffset(Math.max(-limit, Math.min(limit, adjusted)));
    }

    private void applyOffset(float value) {
        offset = value;
        getChildAt(currentPage).setTranslationX(value);
        if (targetPage >= 0 && getWidth() > 0) {
            float direction = targetPage > currentPage ? 1 : -1;
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) direction = -direction;
            getChildAt(targetPage).setTranslationX(value + direction * getWidth());
            float fraction = Math.min(1f, Math.abs(value) / getWidth());
            int position = Math.min(currentPage, targetPage);
            if (listener != null) listener.onPageScrolled(position, targetPage > currentPage ? fraction : 1f - fraction);
        } else if (listener != null) {
            listener.onPageScrolled(currentPage, 0);
        }
    }

    private void settle(boolean changePage) {
        dragging = false;
        final int destination = changePage ? targetPage : currentPage;
        float end = changePage ? Math.copySign(getWidth(), offset) : 0f;
        animator = ValueAnimator.ofFloat(offset, end);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> applyOffset((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animator = null;
                if (destination != currentPage && listener != null) {
                    listener.onPageSelected(destination);
                }
                setCurrentPage(destination);
                if (listener != null) listener.onPageScrolled(destination, 0);
            }
        });
        animator.start();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) velocityTracker.recycle();
        velocityTracker = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }
}
