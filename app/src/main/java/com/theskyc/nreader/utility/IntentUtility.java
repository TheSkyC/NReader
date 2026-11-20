package com.theskyc.nreader.utility;

import android.app.Activity;
import android.content.Intent;

public class IntentUtility extends Intent {

    public static void startAnotherActivity(Activity activity, Intent intent) {
        activity.runOnUiThread(() -> activity.startActivity(intent));
    }

}
