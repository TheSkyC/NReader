// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.view.View;
import android.view.ViewGroup;

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
        setMargins(root.findViewById(R.id.toolbar), safe.left, safe.top, safe.right, 0);
        setMargins(root.findViewById(R.id.master_layout), safe.left, 0, safe.right, safe.bottom);
        setMargins(root.findViewById(R.id.page_text), safe.left, safe.top, safe.right, 0);
    }

    private static void setMargins(View view, int left, int top, int right, int bottom) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.leftMargin == left && params.topMargin == top
            && params.rightMargin == right && params.bottomMargin == bottom) return;
        params.setMargins(left, top, right, bottom);
        view.setLayoutParams(params);
    }
}
