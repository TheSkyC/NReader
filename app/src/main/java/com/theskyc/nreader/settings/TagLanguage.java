package com.theskyc.nreader.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.theskyc.nreader.R;
import com.theskyc.nreader.async.database.TagTranslationCatalog;

import java.util.Locale;

public final class TagLanguage {
    private static SharedPreferences observedPreferences;
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private TagLanguage() {}

    private static synchronized SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences("Settings", 0);
        if (observedPreferences != preferences) {
            if (observedPreferences != null) observedPreferences.unregisterOnSharedPreferenceChangeListener(listener);
            String key = context.getString(R.string.preference_key_tag_display_language);
            String formatKey = context.getString(R.string.preference_key_tag_display_format);
            listener = (shared, changedKey) -> {
                if (changedKey == null || key.equals(changedKey) || formatKey.equals(changedKey))
                    TagTranslationCatalog.notifyDisplayLanguageChanged();
            };
            observedPreferences = preferences;
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
        return preferences;
    }

    public static String selected(Context context) {
        Object value = preferences(context).getAll().get(context.getString(R.string.preference_key_tag_display_language));
        return value instanceof String && TagTranslationCatalog.isSupportedLocale((String) value) ? (String) value : "";
    }

    public static Locale current(Context context) {
        String selected = selected(context);
        return selected.isEmpty() ? context.getResources().getConfiguration().getLocales().get(0)
            : Locale.forLanguageTag(selected);
    }

    public static void select(Context context, String locale) {
        if (!locale.isEmpty() && !TagTranslationCatalog.isSupportedLocale(locale)) {
            throw new IllegalArgumentException("Invalid tag display locale");
        }
        preferences(context).edit().putString(context.getString(R.string.preference_key_tag_display_language), locale).apply();
    }
}
