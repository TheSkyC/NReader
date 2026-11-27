package com.theskyc.nreader.components.cache;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.theskyc.nreader.R;

public final class ImageCacheQuota {
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long DEFAULT_BYTES = 250L * MEBIBYTE;

    private ImageCacheQuota() {
    }

    public static long configuredBytes(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String value;
        try {
            value = preferences.getString(context.getString(R.string.preference_key_image_cache_quota), "250");
        } catch (ClassCastException exception) {
            value = "250";
        }
        return bytesForValue(value);
    }

    static long bytesForValue(String value) {
        if ("512".equals(value)) return 512L * MEBIBYTE;
        if ("1024".equals(value)) return 1024L * MEBIBYTE;
        return DEFAULT_BYTES;
    }
}
