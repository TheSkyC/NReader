package com.theskyc.nreader.components.widgets;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.theskyc.nreader.R;

public class TagSearchResultsLayout extends LinearLayout {
    private boolean compact;

    public TagSearchResultsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View tabs = findViewById(R.id.tabs);
        View pages = findViewById(R.id.container);
        boolean useCompact = tabs != null && pages != null
            && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
            && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
            && MeasureSpec.getSize(heightMeasureSpec) < getResources().getDimensionPixelSize(R.dimen.tag_search_compact_height);
        if (compact != useCompact) {
            compact = useCompact;
            setOrientation(compact ? HORIZONTAL : VERTICAL);
            LayoutParams tabsLayout = (LayoutParams) tabs.getLayoutParams();
            tabsLayout.width = compact ? 0 : LayoutParams.MATCH_PARENT;
            tabsLayout.height = compact ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
            tabsLayout.weight = compact ? 0.4f : 0f;
            tabs.setLayoutParams(tabsLayout);
            LayoutParams pagesLayout = (LayoutParams) pages.getLayoutParams();
            pagesLayout.width = compact ? 0 : LayoutParams.MATCH_PARENT;
            pagesLayout.height = compact ? LayoutParams.MATCH_PARENT : 0;
            pagesLayout.weight = compact ? 0.6f : 1f;
            pages.setLayoutParams(pagesLayout);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
