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
        assertIconButton(R.layout.bookmark_layout, R.id.remove_button);
        assertIconButton(R.layout.reader_bookmark_item, R.id.reader_bookmark_remove);
        assertIconButton(R.layout.page_changer, R.id.prev);
        assertIconButton(R.layout.page_changer, R.id.next);
        assertIconButton(R.layout.zoom_manager, R.id.reader_page_preview);
        assertIconButton(R.layout.zoom_manager, R.id.reader_auto_scroll);
        assertIconButton(R.layout.zoom_manager, R.id.reader_settings);
        assertIconButton(R.layout.comment_layout, R.id.close);
        assertIconButton(R.layout.gallery_state_strip, R.id.gallery_favorite);
        assertIconButton(R.layout.gallery_state_strip, R.id.gallery_local_files);
    }

    private void assertIconButton(int layout, int id) {
        assertIconButton(layout, id, 48);
    }

    private void assertIconButton(int layout, int id, int widthDp) {
        Application application = RuntimeEnvironment.getApplication();
        application.setTheme(R.style.AppTheme_Material3);
        View root = LayoutInflater.from(application).inflate(layout, null, false);
        View button = root.findViewById(id);
        int target = Math.round(48 * application.getResources().getDisplayMetrics().density);
        int expectedWidth = Math.round(widthDp * application.getResources().getDisplayMetrics().density);

        assertTrue("Expected an AppCompat image button", button instanceof AppCompatImageButton);
        assertEquals("Unexpected button width", expectedWidth, button.getLayoutParams().width);
        assertEquals("Unexpected button height", target, button.getLayoutParams().height);
    }
}
