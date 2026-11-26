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
        int originalAccessibility = content.getImportantForAccessibility();
        Runnable showContent = () -> {
            if (searchView.isShowing() && content.getParent() == originalParent) {
                originalParent.removeView(content);
                content.setImportantForAccessibility(originalAccessibility);
                searchView.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        };
        searchView.addTransitionListener((view, previousState, newState) -> {
            if (newState == SearchView.TransitionState.SHOWING) {
                showContent.run();
            } else if (newState == SearchView.TransitionState.SHOWN && content.getParent() == originalParent) {
                // Restoring a SearchView can enter SHOWN during hierarchy state restoration.
                searchView.post(showContent);
            } else if (newState == SearchView.TransitionState.HIDDEN && content.getParent() != originalParent) {
                ((ViewGroup) content.getParent()).removeView(content);
                originalParent.addView(content, originalIndex, originalLayout);
            }
        });
    }
}
