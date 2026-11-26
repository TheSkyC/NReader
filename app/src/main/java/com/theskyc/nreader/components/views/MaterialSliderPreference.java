// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.slider.Slider;
import com.theskyc.nreader.R;

import java.text.NumberFormat;

public final class MaterialSliderPreference extends Preference {
    private final int minValue;
    private final int maxValue;
    private final int stepSize;
    private final boolean valueIsPercent;
    private int value;
    private boolean trackingTouch;
    private Slider boundSlider;
    private Slider.OnChangeListener boundChangeListener;
    private Slider.OnSliderTouchListener boundTouchListener;

    public MaterialSliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray attributes = context.obtainStyledAttributes(
            attrs,
            androidx.preference.R.styleable.SeekBarPreference
        );
        minValue = attributes.getInt(
            androidx.preference.R.styleable.SeekBarPreference_min,
            0
        );
        maxValue = attributes.getInt(
            androidx.preference.R.styleable.SeekBarPreference_android_max,
            100
        );
        stepSize = Math.max(1, attributes.getInt(
            androidx.preference.R.styleable.SeekBarPreference_seekBarIncrement,
            1
        ));
        attributes.recycle();

        TypedArray displayAttributes = context.obtainStyledAttributes(attrs, R.styleable.MaterialSliderPreference);
        valueIsPercent = displayAttributes.getBoolean(R.styleable.MaterialSliderPreference_sliderValueIsPercent, false);
        displayAttributes.recycle();

        if (minValue >= maxValue || (maxValue - minValue) % stepSize != 0) {
            throw new IllegalArgumentException("Invalid Material slider range");
        }
        setLayoutResource(R.layout.preference_material_slider);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray attributes, int index) {
        return attributes.getInt(index, minValue);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        int fallback = defaultValue instanceof Number ? ((Number) defaultValue).intValue() : minValue;
        setValue(restorePersistedValue ? getPersistedInt(fallback) : fallback);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        Slider slider = (Slider) holder.findViewById(R.id.preference_slider);
        TextView valueView = (TextView) holder.findViewById(R.id.preference_slider_value);
        if (slider == null || valueView == null) return;

        if (boundSlider != null) {
            if (boundChangeListener != null) boundSlider.removeOnChangeListener(boundChangeListener);
            if (boundTouchListener != null) boundSlider.removeOnSliderTouchListener(boundTouchListener);
        }

        trackingTouch = false;
        slider.setValueTo(maxValue);
        slider.setValueFrom(minValue);
        slider.setStepSize(stepSize);
        slider.setValue(value);
        slider.setContentDescription(getTitle());
        slider.setLabelFormatter(number -> formatValue(Math.round(number)));
        valueView.setText(formatValue(value));

        boundChangeListener = (changedSlider, newValue, fromUser) -> {
            value = Math.round(newValue);
            valueView.setText(formatValue(value));
            if (fromUser && !trackingTouch) persistInt(value);
        };
        boundTouchListener = new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider changedSlider) {
                trackingTouch = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider changedSlider) {
                trackingTouch = false;
                persistInt(value);
            }
        };
        slider.addOnChangeListener(boundChangeListener);
        slider.addOnSliderTouchListener(boundTouchListener);
        boundSlider = slider;
    }

    private void setValue(int newValue) {
        value = Math.max(minValue, Math.min(maxValue, newValue));
    }

    private String formatValue(int number) {
        if (!valueIsPercent) return Integer.toString(number);
        NumberFormat format = NumberFormat.getPercentInstance(getContext().getResources().getConfiguration().getLocales().get(0));
        return format.format(number / 100.0);
    }
}
