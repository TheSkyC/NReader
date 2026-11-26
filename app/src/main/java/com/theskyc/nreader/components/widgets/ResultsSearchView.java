package com.theskyc.nreader.components.widgets;

import android.content.Context;
import android.util.AttributeSet;

import androidx.activity.BackEventCompat;

import com.google.android.material.search.SearchView;

public class ResultsSearchView extends SearchView {
    private Runnable prepareResults;

    public ResultsSearchView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setPrepareResults(Runnable prepareResults) {
        this.prepareResults = prepareResults;
    }

    @Override
    public void show() {
        if (prepareResults == null) {
            super.show();
        } else if (!isShowing()) {
            prepareResults.run();
            setVisible(true);
            if (isAutoShowKeyboard()) requestFocusAndShowKeyboard();
        }
    }

    @Override
    public void hide() {
        if (prepareResults == null) {
            super.hide();
        } else if (isShowing()) {
            clearFocusAndHideKeyboard();
            setVisible(false);
        }
    }

    @Override
    public void startBackProgress(BackEventCompat event) {
        if (prepareResults == null) super.startBackProgress(event);
    }

    @Override
    public void updateBackProgress(BackEventCompat event) {
        if (prepareResults == null) super.updateBackProgress(event);
    }

    @Override
    public void cancelBackProgress() {
        if (prepareResults == null) super.cancelBackProgress();
    }

    @Override
    public void handleBackInvoked() {
        if (prepareResults == null) super.handleBackInvoked();
        else hide();
    }
}
