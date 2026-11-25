package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Color;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.theskyc.nreader.adapters.StatusManagerAdapter;
import com.theskyc.nreader.components.status.Status;
import com.theskyc.nreader.components.status.StatusColorPickerDialog;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.components.status.StatusNameInput;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class StatusEditorTest {
    @After
    public void resetStatuses() {
        StatusManager.replaceAll(Collections.emptyList());
    }

    @Test
    public void invalidCreationKeepsDialogAndEnteredName() {
        StatusManager.replaceAll(Collections.singletonList(new Status(Color.RED, "Reading")));
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme_Material3);
            controller.setup();
            StatusManagerAdapter adapter = new StatusManagerAdapter(activity);
            ReflectionHelpers.callInstanceMethod(adapter, "updateStatus",
                ReflectionHelpers.ClassParameter.from(Status.class, null));
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText name = dialog.findViewById(R.id.name);
            TextInputLayout input = dialog.findViewById(R.id.name_input);

            name.setText("R");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertEquals("R", name.getText().toString());
            assertEquals(activity.getString(R.string.name_too_short), input.getError().toString());

            name.setText("Reading");
            assertNull(input.getError());
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertEquals("Reading", name.getText().toString());
            assertEquals(activity.getString(R.string.duplicated_name), input.getError().toString());
            dialog.dismiss();
        }
    }

    @Test
    public void editingCanKeepItsNameButCannotUseAnotherStatusName() {
        Status reading = new Status(Color.RED, "Reading");
        StatusManager.replaceAll(Arrays.asList(reading, new Status(Color.BLUE, "Completed")));
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme_Material3);
            controller.setup();
            TextInputLayout input = (TextInputLayout) activity.getLayoutInflater()
                .inflate(R.layout.dialog_add_status, null).findViewById(R.id.name_input);
            input.getEditText().setText("Completed");
            assertFalse(StatusNameInput.validate(input, reading));
            assertEquals(activity.getString(R.string.duplicated_name), input.getError().toString());
            input.getEditText().setText("Reading");
            assertTrue(StatusNameInput.validate(input, reading));
            assertNull(input.getError());
        }
    }

    @Test
    public void materialColorPickerPreservesInitialColorAndOnlyCommitsOnConfirm() {
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme_Material3);
            controller.setup();
            int[] selected = {Color.BLUE};

            StatusColorPickerDialog.show(activity, Color.BLUE, color -> selected[0] = color);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(240f, ((Slider) dialog.findViewById(R.id.color_hue)).getValue(), 1f);
            assertEquals(100f, ((Slider) dialog.findViewById(R.id.color_saturation)).getValue(), 1f);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertEquals(Color.BLUE, selected[0]);

            StatusColorPickerDialog.show(activity, Color.BLUE, color -> selected[0] = color);
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ((Slider) dialog.findViewById(R.id.color_hue)).setValue(0f);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick();
            assertFalse(dialog.isShowing());
            assertEquals(Color.RED, selected[0]);
        }
    }
}
