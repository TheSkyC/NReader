package com.theskyc.nreader.components.widgets;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;

public class MultilineMaterialToolbar extends MaterialToolbar {
    public MultilineMaterialToolbar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        if (TextUtils.isEmpty(title)) return;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child instanceof TextView && TextUtils.equals(((TextView) child).getText(), title)) {
                TextView titleView = (TextView) child;
                titleView.setSingleLine(false);
                titleView.setEllipsize(null);
                return;
            }
        }
    }
}
