package com.theskyc.nreader.components.status;

import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputLayout;
import com.theskyc.nreader.R;

public final class StatusNameInput {
    private StatusNameInput() {
    }

    public static void clearErrorOnEdit(TextInputLayout input) {
        input.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                input.setError(null);
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
    }

    public static boolean validate(TextInputLayout input, @Nullable Status currentStatus) {
        String name = input.getEditText().getText().toString();
        if (name.length() < 2) {
            input.setError(input.getContext().getString(R.string.name_too_short));
            return false;
        }
        if (StatusManager.getByName(name) != null
            && (currentStatus == null || !name.equals(currentStatus.name))) {
            input.setError(input.getContext().getString(R.string.duplicated_name));
            return false;
        }
        input.setError(null);
        return true;
    }
}
