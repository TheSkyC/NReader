// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.widget.TextView;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.network.NetworkUtil;

public final class ReaderLoadErrorMessage {
    private ReaderLoadErrorMessage() {
    }

    public static int getMessageResId(boolean networkPage) {
        return networkPage && NetworkUtil.getType() == NetworkUtil.ConnectionType.UNKNOWN
            ? R.string.reader_page_load_offline
            : R.string.reader_page_load_failed;
    }

    public static void apply(@NonNull TextView message, boolean networkPage) {
        message.setText(getMessageResId(networkPage));
    }
}
