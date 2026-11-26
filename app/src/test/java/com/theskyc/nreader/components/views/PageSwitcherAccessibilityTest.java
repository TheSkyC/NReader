package com.theskyc.nreader.components.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.button.MaterialButton;
import com.theskyc.nreader.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PageSwitcherAccessibilityTest {
    @Test
    public void pageButtonExposesItsActionAndStillOpensThePageSelector() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.AppTheme_Material3);
        controller.setup();
        try {
            PageSwitcher switcher = new PageSwitcher(activity, null);
            switcher.setChanger(new PageSwitcher.DefaultPageChanger());
            activity.setContentView(switcher);
            switcher.setPages(20, 4);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            MaterialButton page = switcher.findViewById(R.id.page_index);
            assertEquals("4 / 20", page.getText().toString());
            assertTrue(page.isFocusable());
            assertTrue(page.isClickable());
            AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(page.createAccessibilityNodeInfo());
            assertEquals("android.widget.Button", node.getClassName());
            assertTrue(node.getActionList().stream().anyMatch(action ->
                action.getId() == AccessibilityNodeInfo.ACTION_CLICK
                    && activity.getString(R.string.change_page).contentEquals(action.getLabel())));

            assertTrue(page.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            ((EditText) dialog.findViewById(R.id.edit_page)).setText("17");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(17, switcher.getActualPage());
            assertEquals("17 / 20", page.getText().toString());

            switcher.findViewById(R.id.next).performClick();
            assertEquals(18, switcher.getActualPage());
            switcher.setActualPage(20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(switcher.findViewById(R.id.next).isEnabled());
            switcher.findViewById(R.id.prev).performClick();
            assertEquals(19, switcher.getActualPage());
        } finally {
            controller.pause().stop().destroy();
        }
    }
}
