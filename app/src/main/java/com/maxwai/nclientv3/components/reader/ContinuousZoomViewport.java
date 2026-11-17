// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;

/** Viewport for resizing the continuous page list around a selected image point. */
public final class ContinuousZoomViewport extends ContinuousReaderView {
    private static final float MAX_SCALE = 3f;
    private float scale = 1f;
    @Nullable private ContinuousZoomAnchor anchor;
    private int anchorImageWidth;
    private int anchorImageHeight;
    private boolean pinchActive;
    private boolean horizontalPan;
    private boolean verticalDrag;
    private float downX;
    private float downY;
    private float downOffset;
    private int viewportWidth;
    private final int touchSlop;
    private final ScaleGestureDetector scaleDetector;

    public ContinuousZoomViewport(@NonNull Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
        setClipChildren(true);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                return pinchActive && anchor != null;
            }

            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                return scaleTo(scale * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pinchActive = false;
            horizontalPan = false;
            verticalDrag = false;
            downX = event.getX();
            downY = event.getY();
            RecyclerView reader = reader();
            downOffset = reader == null ? 0f : reader.getTranslationX();
            endScale();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            float focusX = (event.getX(0) + event.getX(1)) / 2f;
            float focusY = (event.getY(0) + event.getY(1)) / 2f;
            pinchActive = beginScale(focusX, focusY);
            if (pinchActive) {
                RecyclerView reader = reader();
                if (reader != null) reader.stopScroll();
                requestDisallowInterceptTouchEvent(true);
            }
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !pinchActive && scale > 1f) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (!horizontalPan && !verticalDrag) {
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    horizontalPan = true;
                    requestDisallowInterceptTouchEvent(true);
                } else if (Math.abs(dy) > touchSlop) {
                    verticalDrag = true;
                }
            }
            if (horizontalPan) panTo(downOffset + dx);
        }
        scaleDetector.onTouchEvent(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pinchActive = false;
            horizontalPan = false;
            endScale();
        }
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull MotionEvent event) {
        return pinchActive || horizontalPan || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return pinchActive || horizontalPan || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private @Nullable RecyclerView reader() {
        return this;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (scale == 1f) viewportWidth = resolveViewportWidth(width);
    }

    private int resolveViewportWidth(int fallback) {
        return viewportWidth > 0 ? viewportWidth : fallback;
    }

    boolean beginScale(float focusX, float focusY) {
        RecyclerView reader = reader();
        if (reader == null || !(reader.getLayoutManager() instanceof LinearLayoutManager)) return false;
        float localX = focusX - reader.getLeft() - reader.getTranslationX();
        float localY = focusY - reader.getTop();
        View page = reader.findChildViewUnder(localX, localY);
        if (page == null) return false;
        View status = page.findViewById(R.id.continuous_page_status);
        ImageView image = page.findViewById(R.id.continuous_page_image);
        int position = reader.getChildAdapterPosition(page);
        if (status == null || status.getVisibility() != View.GONE || image == null || image.getDrawable() == null
            || position == RecyclerView.NO_POSITION) return false;
        anchor = ContinuousZoomAnchor.capture(position, focusX, focusY,
            reader.getLeft() + reader.getTranslationX() + page.getLeft() + image.getLeft(),
            reader.getTop() + page.getTop() + image.getTop(), image.getWidth(), image.getHeight());
        if (anchor == null) return false;
        anchorImageWidth = image.getWidth();
        anchorImageHeight = image.getHeight();
        return true;
    }

    boolean scaleTo(float requestedScale, float focusX, float focusY) {
        RecyclerView reader = reader();
        if (reader == null || anchor == null || getWidth() <= 0 || !Float.isFinite(requestedScale)
            || !Float.isFinite(focusX) || !Float.isFinite(focusY)
            || !(reader.getLayoutManager() instanceof LinearLayoutManager)) return false;
        viewportWidth = resolveViewportWidth(getWidth());
        if (viewportWidth <= 0) return false;
        float nextScale = Math.max(1f, Math.min(MAX_SCALE, requestedScale));
        int width = Math.max(1, Math.round(viewportWidth * nextScale));
        float factor = width / (float) anchorImageWidth;
        long imageHeight = Math.round(anchorImageHeight * (double) factor);
        if (imageHeight <= 0 || imageHeight > Integer.MAX_VALUE) return false;
        ViewGroup.LayoutParams params = reader.getLayoutParams();
        if (params == null) return false;
        params.width = width;
        reader.setLayoutParams(params);
        reader.requestLayout();
        reader.setTranslationX(anchor.horizontalOffset(focusX, width, viewportWidth));
        ((LinearLayoutManager) reader.getLayoutManager()).scrollToPositionWithOffset(anchor.page,
            anchor.pageTop(focusY, 0, (int) imageHeight, reader.getTop() + reader.getPaddingTop()));
        scale = nextScale;
        return true;
    }

    void endScale() {
        anchor = null;
    }

    public void resetZoom() {
        scale = 1f;
        pinchActive = false;
        horizontalPan = false;
        verticalDrag = false;
        anchor = null;
        RecyclerView reader = reader();
        if (reader == null) return;
        ViewGroup.LayoutParams params = reader.getLayoutParams();
        if (params != null) {
            params.width = 0;
            reader.setLayoutParams(params);
        }
        reader.setTranslationX(0f);
        reader.requestLayout();
    }

    float currentScale() {
        return scale;
    }

    private void panTo(float requestedOffset) {
        RecyclerView reader = reader();
        if (reader == null || !Float.isFinite(requestedOffset)) return;
        viewportWidth = resolveViewportWidth(getWidth());
        if (viewportWidth <= 0) return;
        int width = reader.getLayoutParams().width;
        if (width <= 0) width = Math.round(viewportWidth * scale);
        float minimum = Math.min(0, viewportWidth - Math.max(width, viewportWidth));
        reader.setTranslationX(Math.max(minimum, Math.min(0f, requestedOffset)));
    }
}
