// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import android.widget.TextView;

import androidx.annotation.NonNull;

import com.theskyc.nreader.R;
import com.theskyc.nreader.utility.network.NetworkUtil;

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
