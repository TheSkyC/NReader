// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.theskyc.nreader.R;

/** Keeps system bar visibility changes outside the reader's image geometry. */
public final class ReaderWindowInsets {
    private ReaderWindowInsets() {}

    public static void apply(@NonNull View root, @NonNull WindowInsetsCompat insets) {
        Insets safe = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        root.setPadding(0, 0, 0, 0);
        View toolbarContainer = root.findViewById(R.id.reader_toolbar_container);
        if (toolbarContainer != null) {
            toolbarContainer.setPadding(safe.left, safe.top, safe.right, 0);
        }
        View controls = root.findViewById(R.id.master_layout);
        if (controls != null) {
            controls.setPadding(safe.left, 0, safe.right, safe.bottom);
        }
        View statusOverlay = root.findViewById(R.id.reader_status_overlay);
        if (statusOverlay instanceof ReaderStatusOverlayView) {
            ((ReaderStatusOverlayView) statusOverlay).setSafeInsets(safe);
        }
    }
}
