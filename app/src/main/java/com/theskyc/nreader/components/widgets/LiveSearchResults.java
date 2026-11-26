package com.theskyc.nreader.components.widgets;

import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.search.SearchView;

public final class LiveSearchResults {
    private LiveSearchResults() {
    }

    public static void attach(ResultsSearchView searchView, View content) {
        ViewGroup originalParent = (ViewGroup) content.getParent();
        int originalIndex = originalParent.indexOfChild(content);
        ViewGroup.LayoutParams originalLayout = content.getLayoutParams();
        int originalAccessibility = content.getImportantForAccessibility();
        Runnable moveContent = () -> {
            if (content.getParent() == originalParent) {
                originalParent.removeView(content);
                content.setImportantForAccessibility(originalAccessibility);
                searchView.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        };
        // Material's expanding transition fades the entire results container from zero.
        searchView.setPrepareResults(moveContent);
        searchView.addTransitionListener((view, previousState, newState) -> {
            if (newState == SearchView.TransitionState.SHOWING) {
                moveContent.run();
            } else if (newState == SearchView.TransitionState.SHOWN && content.getParent() == originalParent) {
                // Restoring a SearchView can enter SHOWN during hierarchy state restoration.
                searchView.post(() -> {
                    if (searchView.isShowing()) moveContent.run();
                });
            } else if (newState == SearchView.TransitionState.HIDDEN && content.getParent() != originalParent) {
                ((ViewGroup) content.getParent()).removeView(content);
                originalParent.addView(content, originalIndex, originalLayout);
            }
        });
    }
}
