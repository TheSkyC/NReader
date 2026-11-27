// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Validated, device-local settings for the passive reader status overlay. */
public final class ReaderStatusSettings {
    public static final int ITEM_TIME = 0;
    public static final int ITEM_BATTERY = 1;
    public static final int ITEM_PROGRESS = 2;

    public static final int VISIBILITY_ALWAYS = 0;
    public static final int VISIBILITY_IDLE = 1;

    public static final int BATTERY_ICON_AND_PERCENT = 0;
    public static final int BATTERY_ICON_ONLY = 1;
    public static final int BATTERY_PERCENT_ONLY = 2;

    public static final int PROGRESS_COUNT = 0;
    public static final int PROGRESS_PERCENT = 1;

    public static final int STYLE_BACKED = 0;
    public static final int STYLE_MINIMAL = 1;

    public static final int DEFAULT_IDLE_DELAY_SECONDS = 3;
    public static final int MIN_IDLE_DELAY_SECONDS = 1;
    public static final int MAX_IDLE_DELAY_SECONDS = 30;
    public static final float DEFAULT_TEXT_SIZE_SP = 12f;
    public static final int DEFAULT_BACKGROUND_OPACITY = 58;

    private static final String PREFIX = "reader_status_";
    public static final String KEY_ENABLED = PREFIX + "enabled";
    public static final String KEY_IDLE_DELAY = PREFIX + "idle_delay";
    public static final String KEY_BATTERY_FORMAT = PREFIX + "battery_format";
    public static final String KEY_PROGRESS_FORMAT = PREFIX + "progress_format";
    public static final String KEY_STYLE = PREFIX + "style";
    public static final String KEY_TEXT_SIZE = PREFIX + "text_size";
    public static final String KEY_BACKGROUND_OPACITY = PREFIX + "background_opacity";

    public final boolean enabled;
    public final boolean timeEnabled;
    public final boolean batteryEnabled;
    public final boolean progressEnabled;
    public final int timeVisibility;
    public final int batteryVisibility;
    public final int progressVisibility;
    public final int idleDelaySeconds;
    public final int batteryFormat;
    public final int progressFormat;
    public final int style;
    public final float textSizeSp;
    public final int backgroundOpacity;
    @NonNull public final Position timePosition;
    @NonNull public final Position batteryPosition;
    @NonNull public final Position progressPosition;

    private ReaderStatusSettings(SharedPreferences preferences) {
        enabled = getBoolean(preferences, KEY_ENABLED, true);
        timeEnabled = getBoolean(preferences, PREFIX + "time_enabled", true);
        batteryEnabled = getBoolean(preferences, PREFIX + "battery_enabled", true);
        progressEnabled = getBoolean(preferences, PREFIX + "progress_enabled", true);
        timeVisibility = boundedInt(preferences, PREFIX + "time_visibility", VISIBILITY_ALWAYS,
            VISIBILITY_ALWAYS, VISIBILITY_IDLE);
        batteryVisibility = boundedInt(preferences, PREFIX + "battery_visibility", VISIBILITY_ALWAYS,
            VISIBILITY_ALWAYS, VISIBILITY_IDLE);
        progressVisibility = boundedInt(preferences, PREFIX + "progress_visibility", VISIBILITY_IDLE,
            VISIBILITY_ALWAYS, VISIBILITY_IDLE);
        idleDelaySeconds = boundedInt(preferences, KEY_IDLE_DELAY, DEFAULT_IDLE_DELAY_SECONDS,
            MIN_IDLE_DELAY_SECONDS, MAX_IDLE_DELAY_SECONDS);
        batteryFormat = boundedInt(preferences, KEY_BATTERY_FORMAT, BATTERY_ICON_AND_PERCENT,
            BATTERY_ICON_AND_PERCENT, BATTERY_PERCENT_ONLY);
        progressFormat = boundedInt(preferences, KEY_PROGRESS_FORMAT, PROGRESS_COUNT,
            PROGRESS_COUNT, PROGRESS_PERCENT);
        style = boundedInt(preferences, KEY_STYLE, STYLE_BACKED, STYLE_BACKED, STYLE_MINIMAL);
        textSizeSp = boundedFloat(preferences, KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP, 10f, 18f);
        backgroundOpacity = boundedInt(preferences, KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY,
            30, 90);
        timePosition = readPosition(preferences, ITEM_TIME);
        batteryPosition = readPosition(preferences, ITEM_BATTERY);
        progressPosition = readPosition(preferences, ITEM_PROGRESS);
    }

    @NonNull
    public static ReaderStatusSettings load(@NonNull SharedPreferences preferences) {
        return new ReaderStatusSettings(preferences);
    }

    @NonNull
    public Position positionFor(int item) {
        switch (item) {
            case ITEM_TIME: return timePosition;
            case ITEM_BATTERY: return batteryPosition;
            case ITEM_PROGRESS: return progressPosition;
            default: throw new IllegalArgumentException("Unknown reader status item");
        }
    }

    public boolean itemEnabled(int item) {
        switch (item) {
            case ITEM_TIME: return timeEnabled;
            case ITEM_BATTERY: return batteryEnabled;
            case ITEM_PROGRESS: return progressEnabled;
            default: return false;
        }
    }

    public int visibilityFor(int item) {
        switch (item) {
            case ITEM_TIME: return timeVisibility;
            case ITEM_BATTERY: return batteryVisibility;
            case ITEM_PROGRESS: return progressVisibility;
            default: return VISIBILITY_ALWAYS;
        }
    }

    public boolean hasIdleItems() {
        return timeVisibility == VISIBILITY_IDLE || batteryVisibility == VISIBILITY_IDLE
            || progressVisibility == VISIBILITY_IDLE;
    }

    @NonNull
    public static String preferenceKey(int item, @NonNull String suffix) {
        String name;
        switch (item) {
            case ITEM_TIME: name = "time"; break;
            case ITEM_BATTERY: name = "battery"; break;
            case ITEM_PROGRESS: name = "progress"; break;
            default: throw new IllegalArgumentException("Unknown reader status item");
        }
        return PREFIX + name + "_" + suffix;
    }

    public static final class Position {
        public final boolean custom;
        public final float x;
        public final float y;

        private Position(boolean custom, float x, float y) {
            this.custom = custom;
            this.x = clampUnit(x);
            this.y = clampUnit(y);
        }

        @NonNull
        public static Position defaultPosition() {
            return new Position(false, 1f, 1f);
        }

        @NonNull
        public static Position custom(float x, float y) {
            return new Position(true, x, y);
        }

        private static float clampUnit(float value) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
            return Math.max(0f, Math.min(1f, value));
        }
    }

    private static Position readPosition(SharedPreferences preferences, int item) {
        String customKey = preferenceKey(item, "position_custom");
        if (!getBoolean(preferences, customKey, false)) return Position.defaultPosition();
        float x = getFloat(preferences, preferenceKey(item, "position_x"), Float.NaN);
        float y = getFloat(preferences, preferenceKey(item, "position_y"), Float.NaN);
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y))
            return Position.defaultPosition();
        return Position.custom(x, y);
    }

    private static int boundedInt(SharedPreferences preferences, String key, int fallback,
                                  int minimum, int maximum) {
        int value = getInt(preferences, key, fallback);
        return value < minimum || value > maximum ? fallback : value;
    }

    private static float boundedFloat(SharedPreferences preferences, String key, float fallback,
                                      float minimum, float maximum) {
        float value = getFloat(preferences, key, fallback);
        return Float.isNaN(value) || Float.isInfinite(value) || value < minimum || value > maximum
            ? fallback : value;
    }

    private static boolean getBoolean(SharedPreferences preferences, String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static int getInt(SharedPreferences preferences, String key, int fallback) {
        try {
            return preferences.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static float getFloat(SharedPreferences preferences, String key, float fallback) {
        try {
            return preferences.getFloat(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }
}
