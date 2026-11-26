package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.theskyc.nreader.adapters.StatusManagerAdapter;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.components.status.Status;
import com.theskyc.nreader.components.status.StatusColorPickerDialog;
import com.theskyc.nreader.components.status.StatusManager;
import com.theskyc.nreader.components.status.StatusNameInput;
import com.theskyc.nreader.settings.Global;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
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

    @Test
    public void deletingStatusRequiresConfirmationAndRemovesOnlyItsAssociation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.deleteDatabase("Entries.db");
        String detail = "{\"id\":117,\"media_id\":1,\"num_pages\":1,"
            + "\"title\":{\"pretty\":\"Test\"},\"cover\":{\"path\":\"galleries/1/cover.png\"},"
            + "\"thumbnail\":{\"path\":\"galleries/1/thumb.png\"},"
            + "\"pages\":[{\"path\":\"galleries/1/1.png\",\"width\":1,\"height\":1}]}";
        try (DatabaseHelper database = new DatabaseHelper(application);
             ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            database.getWritableDatabase();
            Global.initFromShared(application);
            Status status = StatusManager.add("Test status", Color.BLUE);
            Queries.StatusMangaTable.insert(new Gallery(application, detail, null, false), status);
            StatusManager.replaceAll(Collections.singletonList(status));
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme_Material3);
            controller.setup();
            RecyclerView list = new RecyclerView(activity);
            list.setLayoutManager(new LinearLayoutManager(activity));
            StatusManagerAdapter adapter = new StatusManagerAdapter(activity);
            list.setAdapter(adapter);
            activity.setContentView(list);
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 800, 1000);
            RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(0);
            assertNotNull(row);
            View delete = row.itemView.findViewById(R.id.cancelButton);
            assertEquals(activity.getString(R.string.status_delete_action, status.name), delete.getContentDescription());

            delete.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            assertEquals(activity.getString(R.string.status_delete_message, status.name),
                ((TextView) dialog.findViewById(android.R.id.message)).getText().toString());
            assertEquals(status.name, Queries.StatusMangaTable.getStatus(117).name);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(status.name, Queries.StatusMangaTable.getStatus(117).name);
            assertEquals(2, adapter.getItemCount());

            delete.performClick();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(StatusManager.getByName(status.name));
            assertEquals(StatusManager.DEFAULT_STATUS, Queries.StatusMangaTable.getStatus(117).name);
            assertEquals(1, adapter.getItemCount());
            assertTrue(Queries.GalleryTable.galleryFromId(application, 117) != null);
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    public void colorPickerTracksRemainVisibleInLightTheme() {
        assertColorPickerTracksRemainVisible();
    }

    @Test
    @Config(qualifiers = "night")
    public void colorPickerTracksRemainVisibleInDarkTheme() {
        assertColorPickerTracksRemainVisible();
    }

    @Test
    @Config(qualifiers = "notnight")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void statusSwatchOutlinesRemainVisibleInLightTheme() {
        assertSwatchOutlines(R.style.AppTheme_Material3);
    }

    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void statusSwatchOutlinesRemainVisibleInDarkTheme() {
        assertSwatchOutlines(R.style.AppTheme_Material3);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void statusSwatchOutlinesRemainVisibleInBlackTheme() {
        assertSwatchOutlines(R.style.AppTheme_Material3_Black);
    }

    private void assertSwatchOutlines(int theme) {
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(theme);
            controller.setup();
            int[][] swatches = {
                {R.layout.entry_status, R.id.color},
                {R.layout.dialog_add_status, R.id.color_swatch},
                {R.layout.dialog_status_color_picker, R.id.color_preview}
            };
            for (int[] swatch : swatches) {
                View view = activity.getLayoutInflater().inflate(swatch[0], null).findViewById(swatch[1]);
                Drawable outline = view.getForeground();
                assertNotNull(outline);
                outline.setBounds(0, 0, 100, 100);
                Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                outline.draw(new Canvas(bitmap));
                int stroke = bitmap.getPixel(50, 0);
                int surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface);
                int variant = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant);
                assertTrue(ColorUtils.calculateContrast(stroke, surface) >= 3.0);
                assertTrue(ColorUtils.calculateContrast(stroke, variant) >= 3.0);
                for (int selected : new int[]{surface, variant}) {
                    view.setBackgroundTintList(ColorStateList.valueOf(selected));
                    assertEquals(selected, view.getBackgroundTintList().getDefaultColor());
                    bitmap.eraseColor(Color.TRANSPARENT);
                    view.getForeground().draw(new Canvas(bitmap));
                    assertEquals(stroke, bitmap.getPixel(50, 0));
                    assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 50));
                }
            }
        }
    }

    private void assertColorPickerTracksRemainVisible() {
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme_Material3);
            controller.setup();
            StatusColorPickerDialog.show(activity, Color.BLUE, color -> { });
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            Slider hue = dialog.findViewById(R.id.color_hue);
            Slider saturation = dialog.findViewById(R.id.color_saturation);
            Slider brightness = dialog.findViewById(R.id.color_brightness);
            TextView value = dialog.findViewById(R.id.color_value);
            int surface = MaterialColors.getColor(hue, com.google.android.material.R.attr.colorSurface);

            saturation.setValue(0f);
            for (float level : new float[]{5f, 95f}) {
                brightness.setValue(level);
                assertEquals(level == 5f ? "#0D0D0D" : "#F2F2F2", value.getText().toString());
                for (Slider slider : new Slider[]{hue, saturation, brightness}) {
                    int track = slider.getTrackActiveTintList().getColorForState(
                        new int[]{android.R.attr.state_enabled}, Color.TRANSPARENT);
                    double contrast = ColorUtils.calculateContrast(track, surface);
                    assertTrue(String.format("Track #%08X on surface #%08X: %.2f", track, surface, contrast),
                        contrast >= 3.0);
                }
            }
            dialog.dismiss();
        }
    }
}
