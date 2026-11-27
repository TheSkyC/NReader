package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.List;
import java.util.Objects;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalDirectoryDialogTest {
    @Test
    public void selectingDirectoryUsesTheDisplayedFolder() {
        Global.initFromShared(RuntimeEnvironment.getApplication());
        try (ActivityController<LocalActivity> controller = Robolectric.buildActivity(LocalActivity.class).setup()) {
            LocalActivity activity = controller.get();
            List<File> folders = Global.getUsableFolders(activity);
            folders.removeIf(Objects::isNull);
            assertFalse(folders.isEmpty());

            ReflectionHelpers.callInstanceMethod(activity, "showDialogFolderChoose");
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView list = dialog.getListView();
            assertEquals(folders.size(), list.getAdapter().getCount());
            for (int index = 0; index < folders.size(); index++) {
                View row = list.getAdapter().getView(index, null, list);
                assertEquals(folders.get(index).toString(), ((TextView) row).getText().toString());
            }
            int index = folders.size() - 1;
            list.performItemClick(list.getAdapter().getView(index, null, list), index,
                list.getAdapter().getItemId(index));
            assertEquals(new File(folders.get(index), Global.MAINFOLDER_NAME),
                ReflectionHelpers.getField(activity, "folder"));
            assertFalse(dialog.isShowing());
        }
    }
}
