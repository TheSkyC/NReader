package com.theskyc.nreader.components.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.google.android.material.chip.ChipGroup;

import java.util.HashSet;
import java.util.Set;

public class CollapsibleTagGroup extends ChipGroup {
    private final Set<View> unavailable = new HashSet<>();
    @Nullable private View expandControl;
    private boolean expanded;

    public CollapsibleTagGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void configure(View expandControl, boolean expanded) {
        this.expandControl = expandControl;
        this.expanded = expanded;
        requestLayout();
    }

    public void setTagAvailable(View child, boolean available) {
        if (available) unavailable.remove(child);
        else unavailable.add(child);
        requestLayout();
    }

    @Override
    public void onViewRemoved(View child) {
        unavailable.remove(child);
        super.onViewRemoved(child);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (expandControl == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec);
        int start = getPaddingStart();
        int limit = width - getPaddingEnd();
        int position = start;
        int row = 1;
        boolean overflow = false;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (unavailable.contains(child)) {
                child.setVisibility(GONE);
                continue;
            }
            // Hidden children must still be measured so a wider layout can reveal them.
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int marginStart = 0;
            int marginEnd = 0;
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                marginStart = margins.getMarginStart();
                marginEnd = margins.getMarginEnd();
            }
            if (position + marginStart + child.getMeasuredWidth() > limit && !isSingleLine()) {
                row++;
                position = start;
            }
            if (row > 2) overflow = true;
            child.setVisibility(expanded || row <= 2 ? VISIBLE : GONE);
            position += marginStart + child.getMeasuredWidth() + marginEnd + getChipSpacingHorizontal();
        }
        expandControl.setVisibility(overflow ? VISIBLE : GONE);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
