package com.theskyc.nreader.ui;

import android.text.BidiFormatter;
import android.view.View;

import androidx.annotation.Nullable;

import com.theskyc.nreader.R;

import java.util.regex.Pattern;

/** Formats tag names for display without changing the tag used for actions. */
public final class TagLabel {
    private static final Pattern BIDI_CONTROLS =
        Pattern.compile("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private TagLabel() {}

    public static String format(View view, @Nullable String canonical, @Nullable String translation) {
        if (canonical == null || canonical.isEmpty()) return "";
        if (translation == null || translation.equals(canonical)) return canonical;
        String displayName = BIDI_CONTROLS.matcher(translation).replaceAll("").trim();
        String canonicalName = BIDI_CONTROLS.matcher(canonical).replaceAll("");
        if (displayName.isEmpty() || displayName.equals(canonicalName)) return canonicalName;
        BidiFormatter bidi = BidiFormatter.getInstance(view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        return view.getContext().getString(R.string.tag_translation_display_pair,
            bidi.unicodeWrap(displayName), bidi.unicodeWrap(canonicalName));
    }
}
