// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public final class ContinuousReaderView extends RecyclerView {
    public ContinuousReaderView(@NonNull Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
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
