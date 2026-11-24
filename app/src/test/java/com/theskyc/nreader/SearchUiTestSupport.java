package com.theskyc.nreader;

import android.widget.ListView;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.components.widgets.ChipTag;

import org.robolectric.shadows.ShadowDialog;
import org.robolectric.Shadows;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

final class SearchUiTestSupport {
    private SearchUiTestSupport() {}

    static void selectStatus(ChipTag chip, int position) {
        assertTrue(chip.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView choices = dialog.getListView();
        assertNotNull(choices);
        assertTrue(choices.performItemClick(choices.getChildAt(position), position, position));
    }

    static void removeTag(ChipTag chip) {
        assertTrue(chip.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
