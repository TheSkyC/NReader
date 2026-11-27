// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;

public final class ReaderChoiceDialog {
    private ReaderChoiceDialog() { }

    @NonNull
    public static AlertDialog show(@NonNull Context context, @StringRes int title, @NonNull RadioGroup source) {
        ScrollView scrollView = new ScrollView(context);
        RadioGroup choices = new RadioGroup(context);
        choices.setOrientation(RadioGroup.VERTICAL);
        int horizontalPadding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        choices.setPaddingRelative(horizontalPadding, 0, horizontalPadding, 0);
        scrollView.addView(choices, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        for (int index = 0; index < source.getChildCount(); index++) {
            MaterialRadioButton original = (MaterialRadioButton) source.getChildAt(index);
            MaterialRadioButton option = new MaterialRadioButton(context);
            option.setId(View.generateViewId());
            option.setText(original.getText());
            option.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
            choices.addView(option, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            option.setChecked(original.getId() == source.getCheckedRadioButtonId());
            option.setOnClickListener(view -> {
                source.check(original.getId());
                dialog.dismiss();
            });
        }
        dialog.show();
        scrollView.post(() -> {
            MaterialRadioButton selected = choices.findViewById(choices.getCheckedRadioButtonId());
            if (selected == null) return;
            Rect bounds = new Rect();
            selected.getHitRect(bounds);
            scrollView.requestChildRectangleOnScreen(choices, bounds, true);
        });
        return dialog;
    }
}
