package com.theskyc.nreader.components.status;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.theskyc.nreader.R;

public final class StatusColorPickerDialog {
    private StatusColorPickerDialog() {
    }

    public static void show(@NonNull Context context, int initialColor,
                            @NonNull ColorSelectionListener listener) {
        float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_status_color_picker, null);
        Slider hue = content.findViewById(R.id.color_hue);
        Slider saturation = content.findViewById(R.id.color_saturation);
        Slider brightness = content.findViewById(R.id.color_brightness);
        View preview = content.findViewById(R.id.color_preview);
        TextView value = content.findViewById(R.id.color_value);
        hue.setValueFrom(0f);
        hue.setValueTo(359f);
        saturation.setValueFrom(0f);
        saturation.setValueTo(100f);
        brightness.setValueFrom(0f);
        brightness.setValueTo(100f);
        hue.setValue(hsv[0]);
        saturation.setValue(hsv[1] * 100f);
        brightness.setValue(hsv[2] * 100f);

        Runnable updatePreview = () -> {
            hsv[0] = hue.getValue();
            hsv[1] = saturation.getValue() / 100f;
            hsv[2] = brightness.getValue() / 100f;
            int color = Color.HSVToColor(hsv);
            preview.setBackgroundTintList(ColorStateList.valueOf(color));
            String hexColor = String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
            value.setText(hexColor);
            value.setContentDescription(context.getString(R.string.status_color_value, hexColor));
            ColorStateList selectedColor = ColorStateList.valueOf(color);
            saturation.setTrackActiveTintList(selectedColor);
            brightness.setTrackActiveTintList(selectedColor);
            float[] hueColor = {hsv[0], 1f, 1f};
            hue.setTrackActiveTintList(ColorStateList.valueOf(Color.HSVToColor(hueColor)));
        };
        Slider.OnChangeListener onChange = (slider, sliderValue, fromUser) -> updatePreview.run();
        hue.addOnChangeListener(onChange);
        saturation.addOnChangeListener(onChange);
        brightness.addOnChangeListener(onChange);
        updatePreview.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.status_choose_color)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                hsv[0] = hue.getValue();
                hsv[1] = saturation.getValue() / 100f;
                hsv[2] = brightness.getValue() / 100f;
                listener.onColorSelected(Color.HSVToColor(hsv));
                dialog.dismiss();
            });
    }

    public interface ColorSelectionListener {
        void onColorSelected(int color);
    }
}
