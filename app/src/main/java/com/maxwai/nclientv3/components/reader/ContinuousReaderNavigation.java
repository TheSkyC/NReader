// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

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
    private final RecyclerView.OnItemTouchListener touchListener = new RecyclerView.SimpleOnItemTouchListener() {
        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isPendingUnloadedPageTouch(event))
                interruptRestore();
            return false;
        }
    };

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING || state == RecyclerView.SCROLL_STATE_SETTLING)
                    cancelPendingPosition();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (pendingPosition != null) return;
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
        int count = pageCount();
        if (count == 0) return;
        ReadingPosition target = position.clamp(count);
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

    public void dispose() {
        cancelPendingPosition();
        recycler.removeOnItemTouchListener(touchListener);
        recycler.removeOnScrollListener(scrollListener);
    }

    private void interruptRestore() {
        if (pendingPosition == null) return;
        cancelPendingPosition();
        updateVisiblePage();
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
