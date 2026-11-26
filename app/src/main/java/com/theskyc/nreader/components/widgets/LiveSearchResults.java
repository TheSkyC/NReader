package com.theskyc.nreader.components.widgets;

import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.search.SearchView;

public final class LiveSearchResults {
    private LiveSearchResults() {
    }

    public static void attach(SearchView searchView, View content) {
        ViewGroup originalParent = (ViewGroup) content.getParent();
        int originalIndex = originalParent.indexOfChild(content);
        ViewGroup.LayoutParams originalLayout = content.getLayoutParams();
        searchView.addTransitionListener((view, previousState, newState) -> {
            if (newState == SearchView.TransitionState.SHOWING && content.getParent() == originalParent) {
                originalParent.removeView(content);
                searchView.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else if (newState == SearchView.TransitionState.HIDDEN && content.getParent() != originalParent) {
                ((ViewGroup) content.getParent()).removeView(content);
                originalParent.addView(content, originalIndex, originalLayout);
            }
        });
    }
}
