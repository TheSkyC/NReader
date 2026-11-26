package com.theskyc.nreader.components.views;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.theskyc.nreader.R;

public class ReaderControlRow extends LinearLayout {
    public ReaderControlRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View navigation = findViewById(R.id.reader_navigation_group);
        View tools = findViewById(R.id.reader_tool_group);
        TextView pages = findViewById(R.id.pages);
        if (navigation != null && tools != null && pages != null) {
            tools.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int labelWidth = Math.max(pages.getMinWidth(), (int) Math.ceil(
                Layout.getDesiredWidth(pages.getText(), pages.getPaint()))
                + pages.getPaddingLeft() + pages.getPaddingRight());
            int navigationWidth = labelWidth + buttonWidth(R.id.prev) + buttonWidth(R.id.next);
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            boolean stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
                && tools.getMeasuredWidth() > 0
                && navigationWidth + tools.getMeasuredWidth() > availableWidth;
            int orientation = stacked ? VERTICAL : HORIZONTAL;
            if (getOrientation() != orientation) {
                setOrientation(orientation);
                LayoutParams params = (LayoutParams) navigation.getLayoutParams();
                params.width = stacked ? LayoutParams.MATCH_PARENT : 0;
                params.weight = stacked ? 0 : 1;
                navigation.setLayoutParams(params);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int buttonWidth(int id) {
        View button = findViewById(id);
        return button == null || button.getVisibility() == GONE ? 0 : button.getLayoutParams().width;
    }
}
