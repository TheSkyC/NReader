// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.components.classes.ReadingPosition;

import java.util.function.IntConsumer;

public final class ContinuousReaderNavigation implements ReaderNavigation {
    private final RecyclerView recycler;
    private final LinearLayoutManager layout;
    private final IntConsumer onPageChanged;
    private int page;
    @Nullable private ReadingPosition pendingPosition;
    private int lastAppliedHeight = -1;
    private int lastObservedTop = Integer.MIN_VALUE;
    private final ViewTreeObserver.OnGlobalLayoutListener restoreListener = this::applyPendingPosition;
    private final ViewTreeObserver.OnGlobalLayoutListener interruptLayoutListener = this::finishInterruptLayout;
    private boolean interruptLayoutPending;
    @Nullable private View queuedImageResizeItem;
    private boolean touchActive;
    @Nullable private ViewTreeObserver.OnPreDrawListener imageResizeListener;
    @Nullable private View imageResizeItem;
    @Nullable private Drawable imageResizeDrawable;
    private int imageResizePosition = RecyclerView.NO_POSITION;
    private final RecyclerView.OnItemTouchListener touchListener = new RecyclerView.SimpleOnItemTouchListener() {
        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchActive = true;
                cancelImageResize();
                if (interruptLayoutPending) anchorVisiblePage();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                touchActive = false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isPendingUnloadedPageTouch(event))
                interruptRestore();
            return false;
        }
    };

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) cancelImageResize();
                if (state == RecyclerView.SCROLL_STATE_DRAGGING || state == RecyclerView.SCROLL_STATE_SETTLING)
                    interruptRestore();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dx != 0 || dy != 0) cancelImageResize();
                if (pendingPosition != null) {
                    if (dx == 0 && dy == 0) return;
                    interruptRestore();
                }
                if (interruptLayoutPending && (dx != 0 || dy != 0)) anchorVisiblePage();
                updateVisiblePage();
            }
        };

    public ContinuousReaderNavigation(@NonNull RecyclerView recycler, @NonNull LinearLayoutManager layout,
                                      int initialPage, @NonNull IntConsumer onPageChanged) {
        this.recycler = recycler;
        this.layout = layout;
        this.page = initialPage;
        this.onPageChanged = onPageChanged;
        recycler.addOnItemTouchListener(touchListener);
        recycler.addOnScrollListener(scrollListener);
    }

    @Override
    public int currentPage() {
        return page;
    }

    @Override
    public int pageCount() {
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        return adapter == null ? 0 : adapter.getItemCount();
    }

    @Override
    public void goToPage(int requestedPage, boolean smooth) {
        int count = pageCount();
        if (count == 0) return;
        goToPosition(new ReadingPosition(Math.max(0, Math.min(requestedPage, count - 1)) + 1, 0f));
    }

    public void goToPosition(@NonNull ReadingPosition position) {
        cancelImageResize();
        int count = pageCount();
        if (count == 0) return;
        ReadingPosition target = position.clamp(count);
        finishInterruptLayout();
        page = target.page - 1;
        cancelPendingPosition();
        pendingPosition = target;
        lastAppliedHeight = -1;
        lastObservedTop = Integer.MIN_VALUE;
        recycler.getViewTreeObserver().addOnGlobalLayoutListener(restoreListener);
        layout.scrollToPositionWithOffset(page, 0);
        onPageChanged.accept(page);
    }

    @Nullable
    public ReadingPosition currentPosition() {
        if (pendingPosition != null) return pendingPosition;
        View item = layout.findViewByPosition(page);
        if (!isLoaded(item)) return null;
        float offset = Math.max(0, recycler.getPaddingTop() - item.getTop()) / (float) item.getHeight();
        return new ReadingPosition(page + 1, offset);
    }

    public boolean isPositionRestored() {
        if (pendingPosition == null) return true;
        View item = layout.findViewByPosition(page);
        if (!isLoaded(item) || recycler.isLayoutRequested()) return false;
        int desiredTop = recycler.getPaddingTop() - Math.round(item.getHeight() * pendingPosition.offset);
        return item.getTop() == desiredTop
            || lastAppliedHeight == item.getHeight() && lastObservedTop == item.getTop();
    }

    public void dispose() {
        cancelImageResize();
        cancelPendingPosition();
        finishInterruptLayout();
        recycler.removeOnItemTouchListener(touchListener);
        recycler.removeOnScrollListener(scrollListener);
    }

    public void changeImageKeepingPosition(@NonNull View item, @NonNull Runnable change) {
        int position = recycler.getChildAdapterPosition(item);
        ImageView image = item.findViewById(R.id.continuous_page_image);
        boolean touching = recycler instanceof ContinuousReaderView ? ((ContinuousReaderView) recycler).hasActiveTouch() : touchActive;
        if (pendingPosition != null || touching || recycler.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
            || position == RecyclerView.NO_POSITION || image == null || image.getHeight() <= 0
            || layout.findFirstVisibleItemPosition() != position) {
            change.run();
            return;
        }
        if (imageResizeListener != null && imageResizeItem == item && imageResizePosition == position
            && image.getDrawable() == imageResizeDrawable) {
            change.run();
            if (imageResizeItem == item) imageResizeDrawable = image.getDrawable();
            return;
        }
        cancelImageResize();
        int oldImageHeight = image.getHeight();
        int oldImageTop = item.getTop() + (image == item ? 0 : image.getTop());
        int oldItemTop = item.getTop();
        int viewportTop = recycler.getPaddingTop();
        imageResizeItem = item;
        imageResizePosition = position;
        imageResizeListener = () -> {
            boolean sameDrawable = image.getDrawable() == imageResizeDrawable;
            cancelImageResize();
            if (!sameDrawable || recycler.getChildAdapterPosition(item) != position || layout.findViewByPosition(position) != item
                || image.getHeight() <= 0 || recycler.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return true;
            int targetImageTop = ContinuousPageAnchor.mappedTop(oldImageTop, viewportTop, oldImageHeight, image.getHeight());
            int targetItemTop = oldItemTop + targetImageTop - oldImageTop;
            if (item.getTop() == targetItemTop) return true;
            layout.scrollToPositionWithOffset(position, targetItemTop - viewportTop);
            finishInterruptLayout();
            interruptLayoutPending = true;
            queuedImageResizeItem = item;
            recycler.getViewTreeObserver().addOnGlobalLayoutListener(interruptLayoutListener);
            return false;
        };
        recycler.getViewTreeObserver().addOnPreDrawListener(imageResizeListener);
        change.run();
        if (imageResizeItem == item) imageResizeDrawable = image.getDrawable();
    }

    private void cancelImageResize() {
        if (imageResizeListener == null) return;
        if (recycler.getViewTreeObserver().isAlive())
            recycler.getViewTreeObserver().removeOnPreDrawListener(imageResizeListener);
        imageResizeListener = null;
        imageResizeItem = null;
        imageResizeDrawable = null;
        imageResizePosition = RecyclerView.NO_POSITION;
    }

    public void cancelImageTransition(@NonNull View item) {
        if (imageResizeItem == item) cancelImageResize();
        if (queuedImageResizeItem == item) {
            queuedImageResizeItem = null;
            if (!recycler.isComputingLayout()) anchorVisiblePage();
        }
    }

    private void interruptRestore() {
        if (pendingPosition == null) return;
        if (anchorVisiblePage()) {
            finishInterruptLayout();
            interruptLayoutPending = true;
            recycler.getViewTreeObserver().addOnGlobalLayoutListener(interruptLayoutListener);
        }
        cancelPendingPosition();
        updateVisiblePage();
    }

    private boolean anchorVisiblePage() {
        int visible = layout.findFirstVisibleItemPosition();
        View anchor = visible == RecyclerView.NO_POSITION ? null : layout.findViewByPosition(visible);
        if (anchor == null) return false;
        layout.scrollToPositionWithOffset(visible, layout.getDecoratedTop(anchor) - recycler.getPaddingTop());
        return true;
    }

    private void finishInterruptLayout() {
        interruptLayoutPending = false;
        queuedImageResizeItem = null;
        if (recycler.getViewTreeObserver().isAlive())
            recycler.getViewTreeObserver().removeOnGlobalLayoutListener(interruptLayoutListener);
    }

    private boolean isPendingUnloadedPageTouch(@NonNull MotionEvent event) {
        if (pendingPosition == null) return false;
        View item = layout.findViewByPosition(page);
        return item != null && !isLoaded(item) && recycler.findChildViewUnder(event.getX(), event.getY()) == item;
    }

    private void updateVisiblePage() {
        int visible = layout.findFirstVisibleItemPosition();
        if (visible != RecyclerView.NO_POSITION && visible != page) {
            page = visible;
            onPageChanged.accept(page);
        }
    }

    private void applyPendingPosition() {
        if (pendingPosition == null) return;
        View item = layout.findViewByPosition(page);
        if (!isLoaded(item)) return;
        int desiredTop = recycler.getPaddingTop() - Math.round(item.getHeight() * pendingPosition.offset);
        if (lastAppliedHeight == item.getHeight() && lastObservedTop == item.getTop()) return;
        lastAppliedHeight = item.getHeight();
        lastObservedTop = item.getTop();
        if (lastObservedTop == desiredTop) return;
        layout.scrollToPositionWithOffset(page, -Math.round(lastAppliedHeight * pendingPosition.offset));
    }

    private boolean isLoaded(@Nullable View item) {
        if (item == null || item.getHeight() <= 0) return false;
        View status = item.findViewById(R.id.continuous_page_status);
        ImageView image = item.findViewById(R.id.continuous_page_image);
        return status != null && status.getVisibility() == View.GONE && image != null && image.getDrawable() != null;
    }

    private void cancelPendingPosition() {
        pendingPosition = null;
        if (recycler.getViewTreeObserver().isAlive())
            recycler.getViewTreeObserver().removeOnGlobalLayoutListener(restoreListener);
    }
}
