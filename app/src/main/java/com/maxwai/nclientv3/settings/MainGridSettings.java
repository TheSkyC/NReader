// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.maxwai.nclientv3.R;

public final class MainGridSettings {
    private final SharedPreferences preferences;
    private final String compactKey;
    private final String wideKey;

    public MainGridSettings(Context context) {
        this(context, R.string.key_main_grid_compact_columns, R.string.key_main_grid_wide_columns,
            R.string.key_column_port_main, R.string.key_column_land_main);
    }

    public static MainGridSettings forDownloads(Context context) {
        return new MainGridSettings(context, R.string.key_download_grid_compact_columns,
            R.string.key_download_grid_wide_columns, R.string.key_column_port_down, R.string.key_column_land_down);
    }

    private MainGridSettings(Context context, int compactKeyId, int wideKeyId, int portraitKeyId, int landscapeKeyId) {
        preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        compactKey = context.getString(compactKeyId);
        wideKey = context.getString(wideKeyId);
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(compactKey)) {
            editor.putInt(compactKey, clamp(preferences.getInt(
                context.getString(portraitKeyId), 2), 3));
        }
        if (!preferences.contains(wideKey)) {
            editor.putInt(wideKey, clamp(preferences.getInt(
                context.getString(landscapeKeyId), 2), 4));
        }
        editor.apply();
    }

    public static boolean isWide(float widthDp) {
        return widthDp >= 600;
    }

    public static int maximumColumns(float widthDp, float fontScale) {
        float minimumWidth = 116 * Math.max(1, fontScale);
        int fitting = Math.max(1, (int) (widthDp / minimumWidth));
        return Math.min(isWide(widthDp) ? 4 : 3, fitting);
    }

    public int columns(float widthDp, float fontScale) {
        boolean wide = isWide(widthDp);
        int preferred = clamp(preferences.getInt(wide ? wideKey : compactKey, 2), wide ? 4 : 3);
        return Math.min(preferred, maximumColumns(widthDp, fontScale));
    }

    public int nextColumns(float widthDp, float fontScale) {
        int maximum = maximumColumns(widthDp, fontScale);
        if (maximum <= 2) return columns(widthDp, fontScale);
        int current = columns(widthDp, fontScale);
        return current >= maximum ? 2 : current + 1;
    }

    public void cycle(float widthDp, float fontScale) {
        if (maximumColumns(widthDp, fontScale) <= 2) return;
        preferences.edit().putInt(isWide(widthDp) ? wideKey : compactKey,
            nextColumns(widthDp, fontScale)).apply();
    }

    private static int clamp(int columns, int maximum) {
        return Math.max(2, Math.min(maximum, columns));
    }
}
