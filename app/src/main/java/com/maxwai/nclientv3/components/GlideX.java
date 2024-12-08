package com.maxwai.nclientv3.components;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

public class GlideX {

    @Nullable
    public static RequestManager with(View view) {
        try {
            return Glide.with(view);
        } catch (VerifyError | IllegalStateException ignore) {
            return null;
        }
    }

    @Nullable
    public static RequestManager with(Context context) {
        try {
            return Glide.with(context);
        } catch (VerifyError | IllegalStateException ignore) {
            return null;
        }
    }

}
