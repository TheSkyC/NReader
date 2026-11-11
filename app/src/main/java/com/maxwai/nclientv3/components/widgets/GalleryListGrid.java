// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.widgets;

import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.MainGridSettings;

public final class GalleryListGrid implements View.OnLayoutChangeListener, RecyclerView.OnChildAttachStateChangeListener {
    private final RecyclerView recycler;
    private final CustomGridLayoutManager manager;
    private final MainGridSettings settings;
    private final Runnable refresh = this::refresh;
    private int requestedColumns = 2;
    private MenuItem menuItem;

    public GalleryListGrid(RecyclerView recycler) {
        this(recycler, new MainGridSettings(recycler.getContext()));
    }

    public GalleryListGrid(RecyclerView recycler, MainGridSettings settings) {
        this.recycler = recycler;
        this.settings = settings;
        manager = new CustomGridLayoutManager(recycler.getContext(), 2);
        manager.setMinimumColumnWidth(Math.round(116 * recycler.getResources().getDisplayMetrics().density));
        recycler.setLayoutManager(manager);
        recycler.addOnLayoutChangeListener(this);
        recycler.addOnChildAttachStateChangeListener(this);
        refresh();
    }

    public void bindMenu(MenuItem item) {
        menuItem = item;
        menuItem.setVisible(true);
        refresh();
    }

    private float widthDp() {
        return (recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight())
            / recycler.getResources().getDisplayMetrics().density;
    }

    private float fontScale() {
        return recycler.getResources().getConfiguration().fontScale;
    }

    public void refresh() {
        if (recycler.getWidth() <= 0) return;
        if (recycler.isComputingLayout()) {
            recycler.removeCallbacks(refresh);
            recycler.post(refresh);
            return;
        }
        int columns = settings.columns(widthDp(), fontScale());
        if (requestedColumns != columns || manager.getSpanCount() != columns) {
            int position = manager.findFirstVisibleItemPosition();
            View anchor = manager.findViewByPosition(position);
            int offset = anchor == null ? 0 : manager.getDecoratedTop(anchor)
                - ((RecyclerView.LayoutParams) anchor.getLayoutParams()).topMargin - recycler.getPaddingTop();
            if (anchor != null) {
                View cover = anchor.findViewById(R.id.gallery_cover);
                if (cover != null) {
                    RecyclerView.LayoutParams margins = (RecyclerView.LayoutParams) anchor.getLayoutParams();
                    int spanWidth = (recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight()) / columns;
                    int coverWidth = spanWidth - margins.leftMargin - margins.rightMargin
                        - (manager.getDecoratedMeasuredWidth(anchor) - cover.getWidth());
                    int rowHeight = manager.getDecoratedMeasuredHeight(anchor) - cover.getHeight()
                        + Math.round(Math.max(0, coverWidth) * 1.5f);
                    // Keep a deeply clipped gallery visible when its new row becomes shorter.
                    if (offset + margins.topMargin + rowHeight <= 0) offset = 0;
                }
            }
            requestedColumns = columns;
            manager.setRequestedColumnCount(columns);
            if (position != RecyclerView.NO_POSITION) manager.scrollToPositionWithOffset(position, offset);
        }
        for (int index = 0; index < recycler.getChildCount(); index++) {
            resizeControls(recycler.getChildAt(index));
        }
        if (menuItem != null) {
            int icon = columns >= 4 ? R.drawable.ic_view_4
                : columns == 3 ? R.drawable.ic_view_3 : R.drawable.ic_view_2;
            menuItem.setIcon(icon);
            String label = recycler.getContext().getString(R.string.list_columns_action,
                columns, settings.nextColumns(widthDp(), fontScale()));
            menuItem.setTitle(label);
            menuItem.setContentDescription(label);
            menuItem.setTooltipText(label);
            menuItem.setEnabled(MainGridSettings.maximumColumns(widthDp(), fontScale()) > 2);
            Global.setTint(recycler.getContext(), menuItem.getIcon());
        }
    }

    public void cycle() {
        if (recycler.getWidth() <= 0 || recycler.isComputingLayout()) return;
        recycler.stopScroll();
        settings.cycle(widthDp(), fontScale());
        refresh();
        if (menuItem != null) recycler.announceForAccessibility(menuItem.getTitle());
    }

    private void resizeControls(View card) {
        boolean dense = requestedColumns >= 3;
        float density = recycler.getResources().getDisplayMetrics().density;
        int padding = Math.round((dense ? 12 : 16) * density);
        int targetSize = Math.round((dense ? 40 : 48) * density);
        int[] buttons = {R.id.gallery_favorite, R.id.gallery_local_files};
        for (int buttonId : buttons) {
            ImageView button = card.findViewById(buttonId);
            if (button != null) {
                if (button.getLayoutParams().width != targetSize || button.getLayoutParams().height != targetSize) {
                    button.getLayoutParams().width = targetSize;
                    button.getLayoutParams().height = targetSize;
                    button.requestLayout();
                }
                ImageView.ScaleType scaleType = dense ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER;
                if (button.getScaleType() != scaleType) button.setScaleType(scaleType);
                int buttonPadding = buttonId == R.id.gallery_favorite && dense
                    && Boolean.TRUE.equals(button.getTag(R.id.gallery_online_favorite))
                    ? Math.round(10 * density) : padding;
                if (button.getPaddingLeft() != buttonPadding)
                    button.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
                if (button.getWidth() != targetSize || button.getHeight() != targetSize) {
                    button.forceLayout();
                    card.requestLayout();
                }
            }
        }
        CircularProgressIndicator progress = card.findViewById(R.id.gallery_download_progress);
        if (progress != null) {
            int size = Math.round((dense ? 34 : 36) * density);
            if (progress.getIndicatorSize() != size) {
                progress.setIndicatorSize(size);
                progress.getLayoutParams().width = size + 2 * progress.getIndicatorInset();
                progress.getLayoutParams().height = size + 2 * progress.getIndicatorInset();
                progress.requestLayout();
            }
        }
    }

    @Override
    public void onChildViewAttachedToWindow(View view) {
        resizeControls(view);
    }

    @Override
    public void onChildViewDetachedFromWindow(View view) {}

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (right - left != oldRight - oldLeft) refresh();
    }

    public void close() {
        recycler.removeOnLayoutChangeListener(this);
        recycler.removeOnChildAttachStateChangeListener(this);
        recycler.removeCallbacks(refresh);
        menuItem = null;
    }
}
