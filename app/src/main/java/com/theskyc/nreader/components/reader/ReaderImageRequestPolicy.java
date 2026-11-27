// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.theskyc.nreader.settings.Global;

public final class ReaderImageRequestPolicy {
    private ReaderImageRequestPolicy() {}

    public static boolean onlyUseCache(@Nullable Uri source, Global.DataUsageType downloadPolicy) {
        if (source == null || downloadPolicy != Global.DataUsageType.NONE) return false;
        String scheme = source.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
