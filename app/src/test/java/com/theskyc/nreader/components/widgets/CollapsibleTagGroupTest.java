package com.theskyc.nreader.components.widgets;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.theskyc.nreader.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CollapsibleTagGroupTest {
    private CollapsibleTagGroup group;
    private View control;

    @Before public void setUp() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        group = new CollapsibleTagGroup(context, null);
        group.setChipSpacingHorizontal(0);
        group.setChipSpacingVertical(0);
        control = new View(context);
        group.configure(control, false);
    }

    @Test public void fitsMaximumPrefixAndRespondsToWidthAndExpansion() {
        for (int i = 0; i < 13; i++) addFixed(50);
        layout(300);
        assertEquals(12, visibleCount());
        assertEquals(View.VISIBLE, control.getVisibility());
        assertEquals(group.getChildAt(0).getTop(), group.getChildAt(5).getTop());
        assertEquals(group.getChildAt(6).getTop(), group.getChildAt(11).getTop());

        layout(100);
        assertEquals(4, visibleCount());
        group.configure(control, true);
        layout(100);
        assertEquals(13, visibleCount());
        group.configure(control, false);
        layout(350);
        assertEquals(13, visibleCount());
        assertEquals(View.GONE, control.getVisibility());
    }

    @Test public void ignoresUnavailableTagsAndHandlesRtlMargins() {
        group.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < 6; i++) {
            View child = addFixed(60);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            params.setMarginStart(10);
            params.setMarginEnd(10);
            child.setLayoutParams(params);
        }
        group.setTagAvailable(group.getChildAt(0), false);
        layout(160);
        assertEquals(4, visibleCount());
        assertEquals(View.GONE, group.getChildAt(0).getVisibility());
        assertEquals(View.GONE, group.getChildAt(5).getVisibility());
        assertEquals(View.VISIBLE, control.getVisibility());
        assertEquals(group.getChildAt(1).getTop(), group.getChildAt(2).getTop());
        assertEquals(group.getChildAt(3).getTop(), group.getChildAt(4).getTop());
    }

    @Test public void changedLabelIsRemeasuredEvenWhenHidden() {
        for (int i = 0; i < 3; i++) addFixed(70);
        TextView label = new TextView(group.getContext());
        label.setSingleLine(true);
        label.setMinWidth(100);
        label.setText("a label that is much longer than the available space");
        group.addView(label, new ChipGroupParams(ViewGroup.LayoutParams.WRAP_CONTENT, 30));
        layout(140);
        assertEquals(View.GONE, label.getVisibility());
        label.setText("x");
        label.setMinWidth(0);
        group.configure(control, false);
        layout(140);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals(View.GONE, control.getVisibility());
    }

    private View addFixed(int width) {
        View child = new View(group.getContext());
        group.addView(child, new ChipGroupParams(width, 30));
        return child;
    }

    private void layout(int width) {
        group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        group.layout(0, 0, width, group.getMeasuredHeight());
    }

    private int visibleCount() {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() == View.VISIBLE) count++;
        }
        return count;
    }

    private static class ChipGroupParams extends com.google.android.material.chip.ChipGroup.LayoutParams {
        ChipGroupParams(int width, int height) {
            super(width, height);
        }
    }
}
