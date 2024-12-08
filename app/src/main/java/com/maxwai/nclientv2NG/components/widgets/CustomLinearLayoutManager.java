package com.maxwai.nclientv2NG.components.widgets;

import android.content.Context;

import androidx.recyclerview.widget.LinearLayoutManager;


public class CustomLinearLayoutManager extends LinearLayoutManager {
    public CustomLinearLayoutManager(Context context) {
        super(context);
    }


    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }
}
