package com.theskyc.nreader.settings;

import android.content.Context;
import android.content.SharedPreferences;
import com.theskyc.nreader.R;

/** Stores how translated tag names are presented without changing tag identity. */
public final class TagDisplayMode {
    public static final String TRANSLATED_ONLY = "translated_only";
    public static final String BILINGUAL = "bilingual";

    private TagDisplayMode() {}

    public static String selected(Context context) {
        String key = context.getString(R.string.preference_key_tag_display_format);
        Object value = context.getApplicationContext()
            .getSharedPreferences("Settings", Context.MODE_PRIVATE)
            .getAll().get(key);
        return BILINGUAL.equals(value) ? BILINGUAL : TRANSLATED_ONLY;
    }

    public static boolean isBilingual(Context context) {
        return BILINGUAL.equals(selected(context));
    }

    public static void normalizePreference(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
            .getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_tag_display_format);
        Object value = preferences.getAll().get(key);
        if (value != null && !TRANSLATED_ONLY.equals(value) && !BILINGUAL.equals(value)) {
            preferences.edit().putString(key, TRANSLATED_ONLY).apply();
        }
    }
}
