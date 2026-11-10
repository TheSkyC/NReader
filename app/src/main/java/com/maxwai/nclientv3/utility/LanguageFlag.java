// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility;

import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.enums.Language;

public final class LanguageFlag {
    private LanguageFlag() {
    }

    public static void bind(@NonNull ImageView view, @NonNull Language language) {
        int image;
        int description;
        switch (language) {
            case CHINESE:
                image = R.drawable.flag_cn;
                description = R.string.language_name_chinese;
                break;
            case ENGLISH:
                image = R.drawable.flag_gb;
                description = R.string.language_name_english;
                break;
            case JAPANESE:
                image = R.drawable.flag_jp;
                description = R.string.language_name_japanese;
                break;
            case ALL:
                image = 0;
                description = 0;
                break;
            default:
                image = R.drawable.flag_unknown;
                description = R.string.unknown_language;
                break;
        }
        ImageViewCompat.setImageTintList(view, null);
        view.setImageResource(image);
        CharSequence text = description == 0 ? null : view.getContext().getText(description);
        view.setContentDescription(text);
        view.setTooltipText(text);
    }
}
