package com.theskyc.nreader.components.views;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.async.downloader.DownloadGalleryV2;

public class RangeSelector extends MaterialAlertDialogBuilder {
    private final Gallery gallery;
    private Slider startSlider, endSlider;

    public RangeSelector(@NonNull Context context, Gallery gallery) {
        super(context);
        this.gallery = gallery;
        View v = View.inflate(context, R.layout.range_selector, null);
        setView(v);
        LinearLayout l1 = v.findViewById(R.id.layout1);
        LinearLayout l2 = v.findViewById(R.id.layout2);
        applyLogic(l1, true);
        applyLogic(l2, false);
        setPositiveButton(R.string.ok, (dialog, which) -> {
            int start = Math.round(startSlider.getValue());
            int end = Math.round(endSlider.getValue());
            if (start <= end)
                DownloadGalleryV2.downloadRange(context, gallery, start, end);
            else
                Toast.makeText(context, R.string.invalid_range_selected, Toast.LENGTH_SHORT).show();
        }).setNegativeButton(R.string.cancel, null);
        setCancelable(true);
    }

    private View.OnClickListener getPrevListener(Slider slider) {
        return v -> slider.setValue(Math.max(0, Math.round(slider.getValue()) - 1));
    }

    private View.OnClickListener getNextListener(Slider slider) {
        return v -> slider.setValue(Math.min(gallery.getPageCount() - 1,
            Math.round(slider.getValue()) + 1));
    }

    private Slider.OnChangeListener getSliderListener(TextView pages) {
        return (slider, value, fromUser) -> pages.setText(getContext().getString(
            R.string.page_format, Math.round(value) + 1, gallery.getPageCount()));
    }

    private void applyLogic(LinearLayout layout, boolean start) {
        ImageButton prev = layout.findViewById(R.id.prev);
        ImageButton next = layout.findViewById(R.id.next);
        TextView pages = layout.findViewById(R.id.pages);
        Slider slider = layout.findViewById(R.id.page_slider);
        int maxProgress = Math.max(0, gallery.getPageCount() - 1);
        slider.setValueTo(Math.max(1, maxProgress));
        slider.setEnabled(maxProgress > 0);
        slider.setContentDescription(getContext().getString(
            start ? R.string.start_page : R.string.end_page));
        slider.setLabelFormatter(value -> Integer.toString(Math.round(value) + 1));
        slider.addOnChangeListener(getSliderListener(pages));
        prev.setOnClickListener(getPrevListener(slider));
        next.setOnClickListener(getNextListener(slider));
        slider.setValue(start ? 0 : maxProgress);
        if (start) startSlider = slider;
        else endSlider = slider;
    }

}
