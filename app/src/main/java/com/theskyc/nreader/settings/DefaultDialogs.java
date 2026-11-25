package com.theskyc.nreader.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.theskyc.nreader.R;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.Locale;

public class DefaultDialogs {
    public static void pageChangerDialog(final Builder builder) {
        MaterialAlertDialogBuilder build = new MaterialAlertDialogBuilder(builder.context);
        if (builder.title != 0) build.setTitle(builder.context.getString(builder.title));
        if (builder.drawable != 0) {
            Drawable icon = ContextCompat.getDrawable(builder.context, builder.drawable);
            if (icon != null) {
                icon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(icon, MaterialColors.getColor(builder.context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(builder.context, R.color.theme_on_surface_variant)));
                build.setIcon(icon);
            }
        }
        View v = View.inflate(builder.context, R.layout.page_changer, null);
        build.setView(v);
        final Slider slider = v.findViewById(R.id.page_slider);
        int maxProgress = Math.max(0, builder.max - builder.min);
        slider.setValueTo(Math.max(1, maxProgress));
        slider.setLabelFormatter(value -> Integer.toString(Math.round(value) + builder.min));
        slider.setEnabled(maxProgress > 0);
        if (Global.useRtl()) slider.setRotationY(180);
        final TextView totalPage = v.findViewById(R.id.page);
        final EditText actualPage = v.findViewById(R.id.edit_page);
        if (builder.title != 0) {
            CharSequence label = builder.context.getText(builder.title);
            slider.setContentDescription(label);
            actualPage.setHint(label);
        }
        v.findViewById(R.id.prev).setOnClickListener(v12 -> {
            int progress = Math.max(0, Math.round(slider.getValue()) - 1);
            slider.setValue(progress);
            actualPage.setText(String.format(Locale.US, "%d", progress + builder.min));

        });
        v.findViewById(R.id.next).setOnClickListener(v1 -> {
            int progress = Math.min(maxProgress, Math.round(slider.getValue()) + 1);
            slider.setValue(progress);
            actualPage.setText(String.format(Locale.US, "%d", progress + builder.min));
        });
        slider.addOnChangeListener((view, value, fromUser) -> {
            if (fromUser)
                actualPage.setText(String.format(Locale.US, "%d", Math.round(value) + builder.min));
        });
        actualPage.setText(String.format(Locale.US, "%d", builder.actual));
        int actualProgress = Math.max(0, Math.min(maxProgress, builder.actual - builder.min));
        slider.setValue(actualProgress);
        totalPage.setText(String.format(Locale.US, "%d", builder.max));
        InputFilter[] filterArray = new InputFilter[1];
        filterArray[0] = new InputFilter.LengthFilter(Integer.toString(builder.max).length());
        actualPage.setFilters(filterArray);
        actualPage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int x;
                try {
                    x = Integer.parseInt(s.toString());
                } catch (NumberFormatException e) {
                    x = -1;
                }
                int progress = Math.max(0, Math.min(maxProgress, x - builder.min));
                slider.setValue(progress);
            }
        });
        if (builder.dialogs != null)
            build
                .setPositiveButton(builder.context.getString(builder.yesbtn), (dialog, id) -> builder.dialogs.positive(Math.round(slider.getValue()) + builder.min))
                .setNegativeButton(builder.context.getString(builder.nobtn), (dialog, which) -> builder.dialogs.negative());
        if (builder.maybebtn != 0)
            build.setNeutralButton(builder.context.getString(builder.maybebtn), (dialog, which) -> builder.dialogs.neutral());
        build.setCancelable(true);
        build.show();
    }

    public interface DialogResults {
        void positive(int actual);

        void negative();

        void neutral();
    }

    public static class CustomDialogResults implements DialogResults {
        @Override
        public void positive(int actual) {
        }

        @Override
        public void negative() {
        }

        @Override
        public void neutral() {
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static class Builder {
        private final Context context;
        DialogResults dialogs;
        private @StringRes
        int title, yesbtn, nobtn, maybebtn;
        private @DrawableRes
        int drawable;
        private int max, actual, min;

        public Builder(Context context) {
            this.context = context;
            title = drawable = 0;
            yesbtn = R.string.ok;
            nobtn = R.string.cancel;
            maybebtn = 0;
            max = actual = 1;
            min = 0;
            dialogs = null;
        }

        public Builder setMin(int min) {
            this.min = min;
            return this;
        }

        public Builder setTitle(int title) {
            this.title = title;
            return this;
        }

        public Builder setYesbtn(int yesbtn) {
            this.yesbtn = yesbtn;
            return this;
        }

        public Builder setNobtn(int nobtn) {
            this.nobtn = nobtn;
            return this;
        }

        public Builder setDrawable(int drawable) {
            this.drawable = drawable;
            return this;
        }

        public Builder setMax(int max) {
            this.max = max;
            return this;
        }

        public Builder setMaybebtn(int maybebtn) {
            this.maybebtn = maybebtn;
            return this;
        }

        public Builder setActual(int actual) {
            this.actual = actual;
            return this;
        }

        public Builder setDialogs(DialogResults dialogs) {
            this.dialogs = dialogs;
            return this;
        }
    }
}
