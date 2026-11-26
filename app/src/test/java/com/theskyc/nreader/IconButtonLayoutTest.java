// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.widget.AppCompatImageButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class IconButtonLayoutTest {
    @Test
    public void secondaryActionsKeepMaterialTouchTargets() {
        assertIconButton(R.layout.activity_random, R.id.favorite);
        assertIconButton(R.layout.activity_random, R.id.share);
        assertIconButton(R.layout.entry_download_layout, R.id.playButton);
        assertIconButton(R.layout.entry_download_layout, R.id.cancelButton);
        assertIconButton(R.layout.entry_download_layout_compact, R.id.playButton);
        assertIconButton(R.layout.entry_download_layout_compact, R.id.cancelButton);
        assertIconButton(R.layout.entry_history, R.id.edit);
        assertIconButton(R.layout.entry_status, R.id.cancelButton);
        assertIconButton(R.layout.item_account, R.id.account_manage);
    }

    private void assertIconButton(int layout, int id) {
        Application application = RuntimeEnvironment.getApplication();
        application.setTheme(R.style.AppTheme_Material3);
        View root = LayoutInflater.from(application).inflate(layout, null, false);
        View button = root.findViewById(id);
        int target = Math.round(48 * application.getResources().getDisplayMetrics().density);

        assertTrue("Expected an AppCompat image button", button instanceof AppCompatImageButton);
        assertEquals("Unexpected button width", target, button.getLayoutParams().width);
        assertEquals("Unexpected button height", target, button.getLayoutParams().height);
    }
}
