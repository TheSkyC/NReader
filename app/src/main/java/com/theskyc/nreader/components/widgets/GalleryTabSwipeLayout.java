// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.widgets;

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

import com.theskyc.nreader.github.chrisbanes.photoview.PhotoView;

public class GalleryTabSwipeLayout extends FrameLayout {
    private static final float EDGE_DRAG_FACTOR = 0.25f;

    private final int touchSlop;
    private final int minimumVelocity;
    private final float edgeInset;
    private float startX;
    private float startY;
    private float dragStartDistance;
    private float offset;
    private boolean dragging;
    private boolean ignored;
    private int currentPage;
    private int targetPage = -1;
    private int settlingPage = -1;
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
        cancelAnimation();
        currentPage = page;
        targetPage = -1;
        settlingPage = -1;
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
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!hasInterruptedSettle()) return super.dispatchTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (ignored) break;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                boolean horizontal = startsHorizontalDrag(dx, dy);
                if (!horizontal && Math.abs(dy) <= touchSlop) break;
                if (horizontal && !canScrollChild(this, startX, startY, dx > 0 ? -1 : 1)) {
                    cancelChildTouch(event);
                    settlingPage = -1;
                    dragging = true;
                    updateDrag(dragStartDistance + dx);
                    return true;
                }
                ignored = true;
                recycleVelocityTracker();
                break;
            case MotionEvent.ACTION_UP:
                if (!ignored) {
                    cancelChildTouch(event);
                    resumeInterruptedSettle();
                    return true;
                }
                // A child handling the gesture needs its terminal event before the pages move.
            case MotionEvent.ACTION_CANCEL:
                boolean handled = super.dispatchTouchEvent(event);
                if (hasInterruptedSettle()) resumeInterruptedSettle();
                return handled;
            case MotionEvent.ACTION_POINTER_DOWN:
                ignored = true;
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean hasInterruptedSettle() {
        return settlingPage >= 0 && animator == null;
    }

    private void resumeInterruptedSettle() {
        settle(settlingPage != currentPage);
        recycleVelocityTracker();
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                ignored = startX < edgeInset || startX > getWidth() - edgeInset;
                dragging = false;
                dragStartDistance = 0f;
                recycleVelocityTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                if (!ignored && animator != null) {
                    cancelAnimation();
                    dragStartDistance = targetPage < 0 ? offset / EDGE_DRAG_FACTOR : offset;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                ignored = true;
                recycleVelocityTracker();
                break;
            case MotionEvent.ACTION_MOVE:
                if (ignored || hasInterruptedSettle() || event.getPointerCount() != 1) break;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                if (startsVerticalDrag(dx, dy)) {
                    ignored = true;
                } else if (startsHorizontalDrag(dx, dy)) {
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
                if (!hasInterruptedSettle()) recycleVelocityTracker();
                break;
            default:
                break;
        }
        return false;
    }

    private static boolean canScrollChild(ViewGroup parent, float x, float y, int direction) {
        for (int index = parent.getChildCount() - 1; index >= 0; index--) {
            View child = parent.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            float localX = x - child.getX();
            float localY = y - child.getY();
            if (localX < 0 || localX >= child.getWidth() || localY < 0 || localY >= child.getHeight()) continue;
            float childX = localX + child.getScrollX();
            float childY = localY + child.getScrollY();
            if (child instanceof PhotoView && ((PhotoView) child).getScale() > 1f) return true;
            if (child instanceof ViewGroup && canScrollChild((ViewGroup) child, childX, childY, direction)) return true;
            if (child.canScrollHorizontally(direction)) return true;
        }
        return false;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility") // Tabs provide accessible click actions.
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return hasInterruptedSettle();
        if (velocityTracker != null) velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateDrag(dragStartDistance + event.getX() - startX);
                break;
            case MotionEvent.ACTION_UP:
                updateDrag(dragStartDistance + event.getX() - startX);
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

    private boolean startsHorizontalDrag(float dx, float dy) {
        return Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f;
    }

    private boolean startsVerticalDrag(float dx, float dy) {
        return Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx);
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
        float adjusted = targetPage < 0 ? distance * EDGE_DRAG_FACTOR : distance;
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
        settlingPage = destination;
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

    private void cancelAnimation() {
        if (animator == null) return;
        animator.removeAllListeners();
        animator.cancel();
        animator = null;
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) velocityTracker.recycle();
        velocityTracker = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (settlingPage >= 0 || dragging) setCurrentPage(currentPage);
        else recycleVelocityTracker();
        super.onDetachedFromWindow();
    }
}
