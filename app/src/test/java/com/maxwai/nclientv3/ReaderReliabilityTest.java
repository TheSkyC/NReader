// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.BaseRequestOptions;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.ImageViewTarget;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.components.views.ZoomFragment;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import android.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReaderReliabilityTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void switchingDirectionKeepsTheCurrentPageWithoutVisitingAnother() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Intent intent = readerIntent(application, folder);
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, intent).setup()) {
                ZoomActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                pager.setCurrentItem(1, false);
                List<Integer> selected = new ArrayList<>();
                pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                    @Override public void onPageSelected(int position) { selected.add(position); }
                });
                ReflectionHelpers.callInstanceMethod(activity, "changeScrollTypeDialog");
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                dialog.getListView().performItemClick(dialog.getListView().getChildAt(1), 1, 1);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.getOrientation());
                assertEquals(1, pager.getCurrentItem());
                assertFalse(selected.contains(2));
                assertEquals(ViewPager2.ORIENTATION_VERTICAL,
                    application.getSharedPreferences("Settings", 0).getInt("zoomScrollType", -1));
            }
        }
    }

    @Test
    public void rotatingLoadedPageRequestsRotationAndRecreatedViewLoadsAgain() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ZoomFragment fragment = ZoomFragment.newInstance(new LocalGallery(folder, true), 0, new LocalGallery(folder, true).getGalleryFolder());
                activity.getSupportFragmentManager().beginTransaction().add(android.R.id.content, fragment, "test-page").commitNow();
                ReflectionHelpers.setField(fragment, "completedDownload", true);
                fragment.rotate();
                ImageViewTarget<?> target = ReflectionHelpers.getField(fragment, "target");
                Request request = target.getRequest();
                assertNotNull(request);
                BaseRequestOptions<?> options = ReflectionHelpers.getField(request, "requestOptions");
                assertEquals(new Rotate(270), options.getTransformations().get(Bitmap.class));
                ReflectionHelpers.setField(fragment, "completedDownload", true);

                activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                assertFalse(ReflectionHelpers.getField(fragment, "completedDownload"));
                activity.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
                ImageViewTarget<?> recreatedTarget = ReflectionHelpers.getField(fragment, "target");
                assertNotNull(recreatedTarget.getRequest());
            }
        }
    }

    private File galleryFolder() throws Exception {
        File folder = temporaryFolder.newFolder();
        byte[] image = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAADwAAAB4CAIAAAAhVwZfAAAA0ElEQVR4nNXOQREAIADDsFL/nocHPlyjIGcbNRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQRIkQf4OvLiuwQPt4CorZgAAAABJRU5ErkJggg==", Base64.DEFAULT);
        for (int page = 1; page <= 3; page++) {
            try (FileOutputStream output = new FileOutputStream(new File(folder, page + ".png"))) {
                output.write(image);
            }
        }
        return folder;
    }

    private Intent readerIntent(Application application, File folder) {
        Intent intent = new Intent(application, ZoomActivity.class);
        intent.putExtra(application.getPackageName() + ".GALLERY", new LocalGallery(folder, true));
        return intent;
    }
}
