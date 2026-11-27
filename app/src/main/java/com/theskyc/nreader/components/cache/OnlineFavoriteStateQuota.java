// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.cache;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.R;

public final class OnlineFavoriteStateQuota {
    public static final int DEFAULT_ENTRIES = 512;

    private OnlineFavoriteStateQuota() {
    }

    public static int configuredEntries(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String value;
        try {
            value = preferences.getString(
                context.getString(R.string.preference_key_online_favorite_state_limit), "512");
        } catch (ClassCastException exception) {
            value = "512";
        }
        return entriesForValue(value);
    }

    public static int entriesForValue(@Nullable String value) {
        if ("2048".equals(value)) return 2048;
        if ("5120".equals(value)) return 5120;
        if ("10240".equals(value)) return 10240;
        return DEFAULT_ENTRIES;
    }
}
