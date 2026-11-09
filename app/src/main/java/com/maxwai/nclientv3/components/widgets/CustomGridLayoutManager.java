// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class CustomGridLayoutManager extends GridLayoutManager {
    private int requestedColumns;
    private int minimumColumnWidth;

    public CustomGridLayoutManager(Context context, int spanCount) {
        super(context, Math.max(1, spanCount));
    }

    public CustomGridLayoutManager(Context context, int spanCount, int orientation, boolean reverseLayout) {
        super(context, Math.max(1, spanCount), orientation, reverseLayout);
    }

    public void setMinimumColumnWidth(int width) {
        if (minimumColumnWidth == 0) requestedColumns = getSpanCount();
        minimumColumnWidth = width;
        requestLayout();
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (minimumColumnWidth > 0 && getOrientation() == VERTICAL && getWidth() > 0) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight();
            int columns = Math.max(1, Math.min(requestedColumns, available / minimumColumnWidth));
            if (getSpanCount() != columns) setSpanCount(columns);
        }
        super.onLayoutChildren(recycler, state);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }
}
