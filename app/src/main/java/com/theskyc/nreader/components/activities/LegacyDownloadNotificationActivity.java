// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.theskyc.nreader.LocalActivity;

public final class LegacyDownloadNotificationActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(null);
        // Old notification extras can contain Parcelable classes from the previous namespace.
        startActivity(new Intent(this, LocalActivity.class));
        finish();
    }
}
