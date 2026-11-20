// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class ContinuousReaderView extends RecyclerView {
    private boolean touchActive;

    public ContinuousReaderView(@NonNull Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) touchActive = true;
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchActive = false;
        return super.dispatchTouchEvent(event);
    }

    boolean hasActiveTouch() {
        return touchActive;
    }

    @Override
    protected void onDetachedFromWindow() {
        touchActive = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        Adapter<?> adapter = getAdapter();
        if (adapter instanceof ContinuousPageAdapter) {
            ((ContinuousPageAdapter) adapter).updateViewportHeight(
                Math.max(0, height - getPaddingTop() - getPaddingBottom()));
        }
    }
}
