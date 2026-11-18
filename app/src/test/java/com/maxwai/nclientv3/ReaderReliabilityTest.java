// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3;

import android.app.Application;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.request.BaseRequestOptions;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.ImageViewTarget;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.maxwai.nclientv3.api.local.LocalGallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.DatabaseHelper;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.database.ReadingProgress;
import com.maxwai.nclientv3.components.classes.ReadingPosition;
import com.maxwai.nclientv3.components.reader.ReaderNavigation;
import com.maxwai.nclientv3.components.reader.ContinuousReaderNavigation;
import com.maxwai.nclientv3.components.reader.ReaderPageRotations;
import com.maxwai.nclientv3.components.reader.ReaderScaleMode;
import com.maxwai.nclientv3.components.reader.SpreadReaderNavigation;
import com.maxwai.nclientv3.components.views.DoublePageFragment;
import com.maxwai.nclientv3.components.views.ZoomFragment;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.github.chrisbanes.photoview.OnViewTapListener;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.network.NetworkUtil;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import android.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

    @Test
    public void restoredPageKeepsTapActionsAndZoomDoesNotDisableSwiping() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                LocalGallery gallery = new LocalGallery(folder, true);
                ZoomFragment first = ZoomFragment.newInstance(gallery, 0, gallery.getGalleryFolder());
                ZoomFragment second = ZoomFragment.newInstance(gallery, 1, gallery.getGalleryFolder());
                activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, first, "f0").add(android.R.id.content, second, "f1").commitNow();
                PhotoView firstPhoto = preparePhoto(first);
                PhotoView secondPhoto = preparePhoto(second);
                ViewPager2 pager = activity.findViewById(R.id.container);

                OnViewTapListener inactiveTap = ReflectionHelpers.getField(secondPhoto.getAttacher(), "mViewTapListener");
                inactiveTap.onViewTap(secondPhoto, 150, 250);
                assertFalse(ReflectionHelpers.getField(activity, "isHidden"));

                secondPhoto.setScale(2f, false);
                assertTrue(pager.isUserInputEnabled());
                firstPhoto.setScale(2f, false);
                assertTrue(pager.isUserInputEnabled());
                firstPhoto.setScale(1f, false);
                assertTrue(pager.isUserInputEnabled());

                controller.recreate();
                activity = controller.get();
                first = (ZoomFragment) activity.getSupportFragmentManager().findFragmentByTag("f0");
                assertNotNull(first);
                firstPhoto = preparePhoto(first);
                OnViewTapListener tap = ReflectionHelpers.getField(firstPhoto.getAttacher(), "mViewTapListener");
                assertNotNull(tap);
                tap.onViewTap(firstPhoto, firstPhoto.getWidth() / 2f, firstPhoto.getHeight() / 2f);
                assertTrue(ReflectionHelpers.getField(activity, "isHidden"));
                firstPhoto.setScale(2f, false);
                assertTrue(((ViewPager2) activity.findViewById(R.id.container)).isUserInputEnabled());
            }
        }
    }

    @Test
    public void pagedScaleModeRecalculatesALoadedImage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                LocalGallery gallery = new LocalGallery(folder, true);
                ZoomFragment fragment = ZoomFragment.newInstance(gallery, 0, gallery.getGalleryFolder(),
                    ReaderScaleMode.FIT_WIDTH);
                controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment, "scale-page").commitNow();
                PhotoView photo = preparePhoto(fragment);
                fragment.setScaleMode(ReaderScaleMode.FIT_WIDTH);
                assertEquals(1.2f, photo.getScale(), 0.01f);
                fragment.setScaleMode(ReaderScaleMode.FIT_SCREEN);
                assertEquals(1f, photo.getScale(), 0.01f);
            }
        }
    }

    @Test
    public void doublePageFragmentHostsBothPagesAndAnOddFinalPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                LocalGallery gallery = new LocalGallery(folder, true);
                DoublePageFragment spread = DoublePageFragment.newInstance(gallery, 1, 2,
                    gallery.getGalleryFolder(), ReaderScaleMode.SMART);
                controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, spread, "double-spread").commitNow();
                assertNotNull(spread.pageFragment(1));
                assertNotNull(spread.pageFragment(2));

                DoublePageFragment last = DoublePageFragment.newInstance(gallery, 2, -1,
                    gallery.getGalleryFolder(), ReaderScaleMode.SMART);
                controller.get().getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, last, "double-last").commitNow();
                assertNotNull(last.pageFragment(2));
                assertNull(last.pageFragment(1));
            }
        }
    }

    @Test
    public void doublePageModeUsesSpreadNavigationAndCanReturnToSinglePage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_double_page", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertTrue(navigation instanceof SpreadReaderNavigation);
                assertEquals(0, navigation.currentPage());
                assertEquals(2, ((ViewPager2) activity.findViewById(R.id.container)).getAdapter().getItemCount());
                ReflectionHelpers.callInstanceMethod(activity, "setDoublePageMode",
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
                navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertFalse(navigation instanceof SpreadReaderNavigation);
                assertEquals(3, navigation.pageCount());
            }
        }
    }

    @Test
    public void doublePageActionsResolveTheCoverAndBothPagesOfTheBodySpread() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_double_page", true).putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup().visible()) {
                ZoomActivity activity = controller.get();
                layoutActivity(activity);
                DoublePageFragment cover = (DoublePageFragment) activity.getSupportFragmentManager().findFragmentByTag("f0");
                assertNotNull(cover);
                assertSame(cover.pageFragment(0), ReflectionHelpers.callInstanceMethod(activity, "getActualFragment"));

                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                layoutActivity(activity);
                DoublePageFragment body = (DoublePageFragment) activity.getSupportFragmentManager().findFragmentByTag("f1");
                assertNotNull(body);
                assertSame(body.pageFragment(1), ReflectionHelpers.callInstanceMethod(activity, "getActualFragment"));
                navigation.goToPage(2, false);
                assertSame(body.pageFragment(2), ReflectionHelpers.callInstanceMethod(activity, "getActualFragment"));

                ReflectionHelpers.callInstanceMethod(activity, "setReaderScaleMode",
                    ReflectionHelpers.ClassParameter.from(int.class, ReaderScaleMode.FIT_WIDTH));
                assertEquals(ReaderScaleMode.FIT_WIDTH, (int) ReflectionHelpers.getField(body.pageFragment(1), "scaleMode"));
                assertEquals(ReaderScaleMode.FIT_WIDTH, (int) ReflectionHelpers.getField(body.pageFragment(2), "scaleMode"));
                assertEquals(ReaderScaleMode.FIT_WIDTH, (int) ReflectionHelpers.getField(cover.pageFragment(0), "scaleMode"));
            }
        }
    }

    @Test
    public void doublePageFragmentFollowsRtlReadingDirection() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        String rtlKey = application.getString(R.string.preference_key_use_rtl);
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean(rtlKey, true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                LocalGallery gallery = new LocalGallery(folder, true);
                DoublePageFragment fragment = DoublePageFragment.newInstance(gallery, 1, 2,
                    gallery.getGalleryFolder(), ReaderScaleMode.SMART);
                controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment, "rtl-spread").commitNow();
                assertEquals(View.LAYOUT_DIRECTION_RTL,
                    fragment.requireView().findViewById(R.id.double_page_root).getLayoutDirection());
            }
        } finally {
            application.getSharedPreferences("Settings", 0).edit().putBoolean(rtlKey, false).commit();
            Global.initFromShared(application);
        }
    }

    @Test
    public void automaticSpreadModeIsRestoredAndShownInSettings() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putInt("reader_spread_mode", 2).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                assertEquals(2, ((Integer) ReflectionHelpers.getField(activity, "spreadMode")).intValue());
                assertFalse((boolean) ReflectionHelpers.getField(activity, "doublePageMode"));
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_spread_auto)).isChecked());
                dialog.dismiss();
            }
        }
    }

    @Test
    public void reachingTheEndShowsRestartAndReturnActions() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(navigation.pageCount() - 1, false);
                activity.changeClosePage(true);
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertNotNull(dialog);
                assertEquals(activity.getString(R.string.reader_end_restart),
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertEquals(activity.getString(R.string.reader_end_close),
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, ((ReaderNavigation) ReflectionHelpers.getField(activity, "readerNavigation")).currentPage());
            }
        }
    }

    @Test
    public void readerMenuJumpsToFirstAndLastPages() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                Menu menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                activity.onOptionsItemSelected(menu.findItem(R.id.jump_last_page));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertEquals(navigation.pageCount() - 1, navigation.currentPage());
                activity.onOptionsItemSelected(menu.findItem(R.id.jump_first_page));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0, navigation.currentPage());
            }
        }
    }

    @Test
    public void readerMenuJumpsToSavedProgress() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".84001").createNewFile());
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                GenericGallery gallery = ReflectionHelpers.getField(activity, "gallery");
                Queries.ReadingProgressTable.insert(gallery.getId(), new ReadingPosition(3, 0f));
                Menu menu = new MenuBuilder(activity);
                activity.onCreateOptionsMenu(menu);
                MenuItem jump = menu.findItem(R.id.jump_saved_position);
                assertTrue(jump.isVisible());
                activity.onOptionsItemSelected(jump);
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertEquals(2, navigation.currentPage());
            }
        }
    }

    private PhotoView preparePhoto(ZoomFragment fragment) {
        PhotoView photo = fragment.requireView().findViewById(R.id.image);
        photo.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        photo.layout(0, 0, 300, 500);
        photo.setImageDrawable(new BitmapDrawable(fragment.getResources(), Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)));
        return photo;
    }

    @Test
    public void pagedImageAccessibilityActionTogglesControlsWhenTouchIsDisabled() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putInt("reader_tap_action", 2).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup().visible()) {
                ZoomActivity activity = controller.get();
                layoutActivity(activity);
                ZoomFragment page = (ZoomFragment) activity.getSupportFragmentManager().findFragmentByTag("f0");
                assertNotNull(page);
                PhotoView photo = page.requireView().findViewById(R.id.image);
                assertEquals(activity.getString(R.string.bookmark_page_format, 1), photo.getContentDescription());
                android.view.accessibility.AccessibilityNodeInfo info = photo.createAccessibilityNodeInfo();
                assertTrue(info.isClickable());
                boolean hasLabeledAction = false;
                for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                    if (action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        && activity.getString(R.string.reader_tap_action_controls).contentEquals(action.getLabel())) {
                        hasLabeledAction = true;
                    }
                }
                assertTrue(hasLabeledAction);
                assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(photo,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
                assertTrue(ReflectionHelpers.getField(activity, "isHidden"));
                assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(photo,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
                assertFalse(ReflectionHelpers.getField(activity, "isHidden"));
            }
        }
    }

    @Test
    public void continuousAccessibilityActionTogglesControlsAndCancelsPendingTap() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).putInt("reader_tap_action", 2).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup().visible()) {
                ZoomActivity activity = controller.get();
                layoutActivity(activity);
                RecyclerView list = activity.findViewById(R.id.continuous_reader);
                RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
                assertNotNull(first);
                assertEquals(activity.getString(R.string.bookmark_page_format, 1), first.itemView.getContentDescription());
                android.view.accessibility.AccessibilityNodeInfo info = first.itemView.createAccessibilityNodeInfo();
                boolean hasLabeledAction = false;
                for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                    if (action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        && activity.getString(R.string.reader_tap_action_controls).contentEquals(action.getLabel())) {
                        hasLabeledAction = true;
                    }
                }
                assertTrue(hasLabeledAction);
                first.itemView.performClick();
                assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(first.itemView,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
                assertTrue(ReflectionHelpers.getField(activity, "isHidden"));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(ViewConfiguration.getDoubleTapTimeout() + 50, TimeUnit.MILLISECONDS);
                assertTrue(ReflectionHelpers.getField(activity, "isHidden"));
                assertTrue(androidx.core.view.ViewCompat.performAccessibilityAction(first.itemView,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
                assertFalse(ReflectionHelpers.getField(activity, "isHidden"));
                list.setAdapter(null);
                androidx.core.view.ViewCompat.performAccessibilityAction(first.itemView,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null);
                assertFalse(ReflectionHelpers.getField(activity, "isHidden"));
            }
        }
    }

    @Test
    public void pagerRestorationRebindsTheCurrentPagesTapHandler() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup().visible()) {
                layoutActivity(controller.get());
                ZoomFragment original = (ZoomFragment) controller.get().getSupportFragmentManager().findFragmentByTag("f0");
                assertNotNull(original);
                controller.recreate();
                ZoomActivity restored = controller.get();
                layoutActivity(restored);
                ZoomFragment page = (ZoomFragment) restored.getSupportFragmentManager().findFragmentByTag("f0");
                assertNotNull(page);
                PhotoView photo = preparePhoto(page);
                OnViewTapListener tap = ReflectionHelpers.getField(photo.getAttacher(), "mViewTapListener");
                assertNotNull(tap);
                tap.onViewTap(photo, 150, 250);
                assertTrue(ReflectionHelpers.getField(restored, "isHidden"));
            }
        }
    }

    @Test
    public void readerSettingsApplyImmediatelyAndPersistAcrossRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                pager.setCurrentItem(1, false);
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertNotNull(dialog);
                dialog.findViewById(R.id.reader_vertical).performClick();
                assertEquals(ViewPager2.ORIENTATION_VERTICAL, pager.getOrientation());
                assertEquals(1, pager.getCurrentItem());
                dialog.findViewById(R.id.reader_orientation_portrait).performClick();
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.getRequestedOrientation());
                assertEquals(1, application.getSharedPreferences("Settings", 0)
                    .getInt("reader_orientation", -1));
                dialog.findViewById(R.id.reader_tap_action_none).performClick();
                assertFalse(((SwitchMaterial) dialog.findViewById(R.id.reader_tap_turn)).isChecked());
                assertEquals(2, application.getSharedPreferences("Settings", 0)
                    .getInt("reader_tap_action", -1));
                dialog.findViewById(R.id.reader_filter_grayscale).performClick();
                assertEquals(1, application.getSharedPreferences("Settings", 0)
                    .getInt("reader_filter", -1));
                dialog.findViewById(R.id.reader_scale_width).performClick();
                assertEquals(ReaderScaleMode.FIT_WIDTH,
                    application.getSharedPreferences("Settings", 0).getInt("reader_scale_mode", -1));
                ((SwitchMaterial) dialog.findViewById(R.id.reader_auto_crop)).setChecked(true);
                assertTrue(application.getSharedPreferences("Settings", 0)
                    .getBoolean("reader_auto_crop", false));
                ((android.widget.SeekBar) dialog.findViewById(R.id.reader_brightness)).setProgress(140);
                ((android.widget.SeekBar) dialog.findViewById(R.id.reader_contrast)).setProgress(70);
                ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).setProgress(125);
                assertEquals(40, application.getSharedPreferences("Settings", 0).getInt("reader_brightness", 0));
                assertEquals(-30, application.getSharedPreferences("Settings", 0).getInt("reader_contrast", 0));
                assertEquals(25, application.getSharedPreferences("Settings", 0).getInt("reader_saturation", 0));
                ((SwitchMaterial) dialog.findViewById(R.id.reader_tap_turn)).setChecked(false);
                ((SwitchMaterial) dialog.findViewById(R.id.reader_volume_turn)).setChecked(false);
                ((SwitchMaterial) dialog.findViewById(R.id.reader_instant_turn)).setChecked(true);
                assertFalse(Global.isButtonChangePage());
                assertFalse(Global.volumeOverride());
                assertTrue(application.getSharedPreferences("Settings", 0).getBoolean("instant_page_turn", false));
                assertFalse(application.getSharedPreferences("Settings", 0).getBoolean("change_page_buttons", true));
                assertFalse(application.getSharedPreferences("Settings", 0).getBoolean("volume_override", true));
                Global.initFromShared(application);
                assertFalse(Global.isButtonChangePage());
                assertFalse(Global.volumeOverride());
                dialog.dismiss();
                controller.recreate();
                activity = controller.get();
                assertEquals(ViewPager2.ORIENTATION_VERTICAL,
                    ((ViewPager2) activity.findViewById(R.id.container)).getOrientation());
                activity.findViewById(R.id.reader_settings).performClick();
                dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_vertical)).isChecked());
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_filter_grayscale)).isChecked());
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_scale_width)).isChecked());
                assertTrue(((SwitchMaterial) dialog.findViewById(R.id.reader_auto_crop)).isChecked());
                assertEquals(140, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_brightness)).getProgress());
                assertEquals(70, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_contrast)).getProgress());
                assertEquals(125, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).getProgress());
                assertFalse(((SwitchMaterial) dialog.findViewById(R.id.reader_tap_turn)).isChecked());
                assertFalse(((SwitchMaterial) dialog.findViewById(R.id.reader_volume_turn)).isChecked());
                assertTrue(((SwitchMaterial) dialog.findViewById(R.id.reader_instant_turn)).isChecked());
                dialog.dismiss();
            }
        }
    }

    @Test
    public void globalTapSwitchReplacesDefaultActionWithoutChangingGalleryOverrides() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File firstFolder = galleryFolder();
        File secondFolder = galleryFolder();
        assertTrue(new File(firstFolder, ".82128").createNewFile());
        assertTrue(new File(secondFolder, ".82129").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("change_page_buttons", false).putInt("reader_tap_action", 2)
            .putInt("reader_tap_action_82128", 2).commit();
        Global.initFromShared(application);
        try (ActivityController<SettingsActivity> settings = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            androidx.preference.SwitchPreferenceCompat tapTurn = settings.get().fragment.findPreference("change_page_buttons");
            assertNotNull(tapTurn);
            assertFalse(tapTurn.isChecked());
            tapTurn.performClick();
            assertTrue(tapTurn.isChecked());
            assertTrue(Global.isButtonChangePage());
            assertFalse(application.getSharedPreferences("Settings", 0).contains("reader_tap_action"));
            assertEquals(2, application.getSharedPreferences("Settings", 0).getInt("reader_tap_action_82128", -1));
        }
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> first = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, firstFolder)).setup()) {
                assertEquals(2, (int) ReflectionHelpers.callInstanceMethod(first.get(), "effectiveReaderTapAction"));
            }
            try (ActivityController<ZoomActivity> second = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, secondFolder)).setup()) {
                assertEquals(1, (int) ReflectionHelpers.callInstanceMethod(second.get(), "effectiveReaderTapAction"));
            }
        }
    }

    @Test
    public void tapActionControlsStayInSyncAndDoNotChangeOtherGalleries() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File firstFolder = galleryFolder();
        File secondFolder = galleryFolder();
        assertTrue(new File(firstFolder, ".82128").createNewFile());
        assertTrue(new File(secondFolder, ".82129").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putInt("reader_tap_action", 2).putBoolean("change_page_buttons", false).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> first = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, firstFolder)).setup()) {
                ZoomActivity activity = first.get();
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                SwitchMaterial tap = dialog.findViewById(R.id.reader_tap_turn);
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_tap_action_none)).isChecked());
                tap.setChecked(true);
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_tap_action_edge)).isChecked());
                assertEquals(1, application.getSharedPreferences("Settings", 0).getInt("reader_tap_action_82128", -1));
                assertEquals(2, application.getSharedPreferences("Settings", 0).getInt("reader_tap_action", -1));
                assertFalse(Global.isButtonChangePage());
                dialog.findViewById(R.id.reader_tap_action_none).performClick();
                assertFalse(tap.isChecked());
                assertEquals(2, application.getSharedPreferences("Settings", 0).getInt("reader_tap_action_82128", -1));
                tap.setChecked(true);
                tap.setChecked(false);
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_tap_action_controls)).isChecked());
                dialog.dismiss();
            }
            try (ActivityController<ZoomActivity> second = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, secondFolder)).setup()) {
                ZoomActivity activity = second.get();
                assertEquals(2, (int) ReflectionHelpers.callInstanceMethod(activity, "effectiveReaderTapAction"));
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_tap_action_none)).isChecked());
                dialog.dismiss();
                application.getSharedPreferences("Settings", 0).edit().remove("reader_tap_action").apply();
                Global.setButtonChangePage(application, true);
                assertEquals(1, (int) ReflectionHelpers.callInstanceMethod(activity, "effectiveReaderTapAction"));
                Global.setButtonChangePage(application, false);
                assertEquals(0, (int) ReflectionHelpers.callInstanceMethod(activity, "effectiveReaderTapAction"));
            }
            try (ActivityController<ZoomActivity> first = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, firstFolder)).setup()) {
                assertEquals(0, (int) ReflectionHelpers.callInstanceMethod(first.get(), "effectiveReaderTapAction"));
            }
        }
    }

    @Test
    public void pagedAppearanceSurvivesViewRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                LocalGallery gallery = new LocalGallery(folder, true);
                ZoomFragment fragment = ZoomFragment.newInstance(gallery, 0, gallery.getGalleryFolder());
                activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment, "appearance-page").commitNow();
                fragment.setScaleMode(ReaderScaleMode.FIT_WIDTH);
                fragment.setFilterMode(2);
                fragment.setAutoCrop(true);
                fragment.setAdjustments(10, 20, -30);
                activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                activity.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
                assertEquals(ReaderScaleMode.FIT_WIDTH, (int) ReflectionHelpers.getField(fragment, "scaleMode"));
                assertEquals(2, (int) ReflectionHelpers.getField(fragment, "filterMode"));
                assertTrue(ReflectionHelpers.getField(fragment, "autoCrop"));
                assertEquals(10, (int) ReflectionHelpers.getField(fragment, "brightness"));
                assertEquals(20, (int) ReflectionHelpers.getField(fragment, "contrast"));
                assertEquals(-30, (int) ReflectionHelpers.getField(fragment, "saturation"));
            }
        }
    }

    @Test
    public void doublePageAppearanceSurvivesViewAndActivityRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                LocalGallery gallery = new LocalGallery(folder, true);
                DoublePageFragment spread = DoublePageFragment.newInstance(gallery, 1, 2,
                    gallery.getGalleryFolder(), ReaderScaleMode.SMART);
                activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, spread, "appearance-spread").commitNow();
                spread.setScaleMode(ReaderScaleMode.FIT_WIDTH);
                spread.setFilterMode(2);
                spread.setAutoCrop(true);
                spread.setAdjustments(10, 20, -30);
                activity.getSupportFragmentManager().beginTransaction().detach(spread).commitNow();
                activity.getSupportFragmentManager().beginTransaction().attach(spread).commitNow();
                assertAppearance(spread.pageFragment(1));
                assertAppearance(spread.pageFragment(2));
                controller.recreate();
                spread = (DoublePageFragment) controller.get().getSupportFragmentManager()
                    .findFragmentByTag("appearance-spread");
                assertNotNull(spread);
                assertAppearance(spread.pageFragment(1));
                assertAppearance(spread.pageFragment(2));
            }
        }
    }

    private void assertAppearance(ZoomFragment fragment) {
        assertNotNull(fragment);
        assertEquals(ReaderScaleMode.FIT_WIDTH, (int) ReflectionHelpers.getField(fragment, "scaleMode"));
        assertEquals(2, (int) ReflectionHelpers.getField(fragment, "filterMode"));
        assertTrue(ReflectionHelpers.getField(fragment, "autoCrop"));
        assertEquals(10, (int) ReflectionHelpers.getField(fragment, "brightness"));
        assertEquals(20, (int) ReflectionHelpers.getField(fragment, "contrast"));
        assertEquals(-30, (int) ReflectionHelpers.getField(fragment, "saturation"));
    }

    @Test
    public void validGalleryFilterDoesNotReadInvalidGlobalFallback() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".82128").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putString("reader_filter", "invalid").putInt("reader_filter_82128", 2).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                assertEquals(2, (int) ReflectionHelpers.getField(controller.get(), "readerFilterMode"));
            }
        }
    }

    @Test
    public void readerPresetUpdatesControlsAndAllowsFurtherChanges() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                dialog.findViewById(R.id.reader_preset_scan).performClick();
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_filter_grayscale)).isChecked());
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_background_white)).isChecked());
                assertTrue(((SwitchMaterial) dialog.findViewById(R.id.reader_auto_crop)).isChecked());
                assertEquals(104, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_brightness)).getProgress());
                assertEquals(118, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_contrast)).getProgress());
                assertEquals(0, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).getProgress());
                assertEquals("-100", ((android.widget.TextView) dialog.findViewById(R.id.reader_saturation_value)).getText().toString());
                assertEquals(-100, application.getSharedPreferences("Settings", 0).getInt("reader_saturation", 0));

                dialog.findViewById(R.id.reader_preset_night).performClick();
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_filter_none)).isChecked());
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_background_black)).isChecked());
                assertFalse(((SwitchMaterial) dialog.findViewById(R.id.reader_auto_crop)).isChecked());
                assertEquals(88, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_brightness)).getProgress());
                assertEquals(108, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_contrast)).getProgress());
                assertEquals(92, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).getProgress());
                ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).setProgress(120);
                assertEquals(20, application.getSharedPreferences("Settings", 0).getInt("reader_saturation", 0));
                dialog.dismiss();
                activity.findViewById(R.id.reader_settings).performClick();
                dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertEquals(120, ((android.widget.SeekBar) dialog.findViewById(R.id.reader_saturation)).getProgress());
                assertTrue(((android.widget.RadioButton) dialog.findViewById(R.id.reader_background_black)).isChecked());
                dialog.dismiss();
            }
        }
    }

    @Test
    public void readerBackgroundAppliesAndPersistsAcrossRecreation() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                assertNotNull(dialog);
                dialog.findViewById(R.id.reader_background_black).performClick();
                assertEquals(android.graphics.Color.BLACK,
                    ((ColorDrawable) activity.findViewById(R.id.container).getBackground()).getColor());
                assertEquals(1, application.getSharedPreferences("Settings", 0)
                    .getInt("reader_background", 0));
                dialog.dismiss();
                controller.recreate();
                activity = controller.get();
                assertEquals(android.graphics.Color.BLACK,
                    ((ColorDrawable) activity.findViewById(R.id.container).getBackground()).getColor());
            }
        }
    }

    @Test
    public void readerBackgroundPreferenceIsScopedPerGalleryWithGlobalFallback() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File darkFolder = galleryFolder();
        File lightFolder = galleryFolder();
        assertTrue(new File(darkFolder, ".83201").createNewFile());
        assertTrue(new File(lightFolder, ".83202").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putInt("reader_background", 3).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> darkController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, darkFolder)).setup()) {
                ZoomActivity dark = darkController.get();
                GenericGallery darkGallery = ReflectionHelpers.getField(dark, "gallery");
                dark.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                dialog.findViewById(R.id.reader_background_black).performClick();
                assertEquals(1, application.getSharedPreferences("Settings", 0)
                    .getInt("reader_background_" + darkGallery.getId(), -1));
                assertEquals(Color.BLACK,
                    ((ColorDrawable) dark.findViewById(R.id.container).getBackground()).getColor());
                dialog.dismiss();
            }
            try (ActivityController<ZoomActivity> lightController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, lightFolder)).setup()) {
                ZoomActivity light = lightController.get();
                GenericGallery lightGallery = ReflectionHelpers.getField(light, "gallery");
                assertEquals(Color.WHITE,
                    ((ColorDrawable) light.findViewById(R.id.container).getBackground()).getColor());
                assertFalse(application.getSharedPreferences("Settings", 0)
                    .contains("reader_background_" + lightGallery.getId()));
            }
            try (ActivityController<ZoomActivity> restoredController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, darkFolder)).setup()) {
                assertEquals(Color.BLACK,
                    ((ColorDrawable) restoredController.get().findViewById(R.id.container).getBackground()).getColor());
            }
        }
    }

    @Test
    public void readerBookmarkTogglesAndUpdatesIcon() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                Menu menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                MenuItem bookmark = menu.findItem(R.id.bookmark);
                activity.onCreateOptionsMenu(menu);
                assertEquals(R.drawable.ic_bookmark_border,
                    ((Integer) ReflectionHelpers.getField(bookmark, "mIconResId")).intValue());
                int galleryId = ((GenericGallery) ReflectionHelpers.getField(activity, "gallery")).getId();
                activity.onOptionsItemSelected(bookmark);
                if (galleryId > 0) {
                    assertEquals(1, Queries.ResumeTable.pageFromId(galleryId));
                    activity.onCreateOptionsMenu(menu);
                    assertEquals(R.drawable.ic_bookmark,
                        ((Integer) ReflectionHelpers.getField(bookmark, "mIconResId")).intValue());
                    activity.onOptionsItemSelected(bookmark);
                    assertEquals(-1, Queries.ResumeTable.pageFromId(galleryId));
                } else {
                    assertEquals(-1, Queries.ResumeTable.pageFromId(galleryId));
                }
            }
        }
    }

    @Test
    public void bookmarkSheetScrollsAndOpensOrDeletesTheSelectedPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".84002").createNewFile());
        for (int page = 4; page <= 20; page++) assertTrue(new File(folder, page + ".png").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Queries.ReaderBookmarkTable.deleteForGallery(84002);
            for (int page = 1; page <= 20; page++) {
                assertTrue(Queries.ReaderBookmarkTable.upsert(84002, page, "Marker " + page, "Note") > 0);
            }
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReflectionHelpers.callInstanceMethod(activity, "showReaderBookmarks");
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                RecyclerView list = dialog.findViewById(R.id.reader_bookmark_list);
                assertNotNull(list);
                assertEquals(20, list.getAdapter().getItemCount());
                list.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, 500, 400);
                list.scrollToPosition(19);
                list.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, 500, 400);
                RecyclerView.ViewHolder last = list.findViewHolderForAdapterPosition(19);
                assertNotNull(last);
                assertEquals(list.getWidth() - list.getPaddingLeft() - list.getPaddingRight(), last.itemView.getWidth());
                View delete = ((android.view.ViewGroup) last.itemView).getChildAt(1);
                assertEquals(activity.getString(R.string.reader_bookmark_delete_page, 20), delete.getContentDescription());
                delete.performClick();
                assertEquals(19, list.getAdapter().getItemCount());
                assertEquals(19, Queries.ReaderBookmarkTable.getForGallery(84002).size());
                list.scrollToPosition(18);
                list.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
                list.layout(0, 0, 500, 400);
                RecyclerView.ViewHolder selected = list.findViewHolderForAdapterPosition(18);
                assertNotNull(selected);
                ((android.view.ViewGroup) selected.itemView).getChildAt(0).performClick();
                assertEquals(18, ((ReaderNavigation) ReflectionHelpers.getField(activity, "readerNavigation")).currentPage());
                assertFalse(dialog.isShowing());
            } finally {
                Queries.ReaderBookmarkTable.deleteForGallery(84002);
            }
        }
    }

    @Test
    public void invalidInstantTurnPreferenceFallsBackAndCanBeReplaced() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putString("instant_page_turn", "invalid").commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                SwitchMaterial instant = dialog.findViewById(R.id.reader_instant_turn);
                assertFalse(instant.isChecked());
                instant.setChecked(true);
                assertTrue(application.getSharedPreferences("Settings", 0).getBoolean("instant_page_turn", false));
                dialog.dismiss();
            }
        }
    }

    @Test
    public void pagePreviewMarksCurrentPageAndJumpsToSelectedPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ViewPager2 pager = activity.findViewById(R.id.container);
                pager.setCurrentItem(1, false);
                activity.findViewById(R.id.reader_page_preview).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                RecyclerView grid = dialog.findViewById(R.id.reader_page_grid);
                assertNotNull(grid);
                assertEquals(3, grid.getAdapter().getItemCount());
                grid.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
                grid.layout(0, 0, 900, 1000);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                RecyclerView.ViewHolder current = grid.findViewHolderForAdapterPosition(1);
                RecyclerView.ViewHolder third = grid.findViewHolderForAdapterPosition(2);
                assertNotNull(current);
                assertNotNull(third);
                assertEquals(activity.getString(R.string.reader_preview_current_page, 2),
                    current.itemView.getContentDescription());
                third.itemView.performClick();
                assertEquals(2, pager.getCurrentItem());
                assertFalse(dialog.isShowing());
            }
        }
    }

    @Test
    public void readerNavigationBoundsPageRequests() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ReaderNavigation navigation = ReflectionHelpers.getField(controller.get(), "readerNavigation");
                assertEquals(3, navigation.pageCount());
                navigation.goToPage(99, false);
                assertEquals(2, navigation.currentPage());
                navigation.goToPage(-1, false);
                assertEquals(0, navigation.currentPage());
            }
        }
    }

    @Test
    public void continuousModeSwitchesAndRetainsCurrentPage() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ((ViewPager2) activity.findViewById(R.id.container)).setCurrentItem(1, false);
                activity.findViewById(R.id.reader_settings).performClick();
                BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                dialog.findViewById(R.id.reader_continuous).performClick();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertTrue(navigation instanceof ContinuousReaderNavigation);
                assertEquals(1, navigation.currentPage());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.continuous_reader).getVisibility());
                navigation.goToPage(2, false);
                assertEquals(2, navigation.currentPage());
                dialog.findViewById(R.id.reader_vertical).performClick();
                assertEquals(2, ((ViewPager2) activity.findViewById(R.id.container)).getCurrentItem());
                assertEquals(View.GONE, activity.findViewById(R.id.continuous_reader).getVisibility());
                dialog.findViewById(R.id.reader_continuous).performClick();
                dialog.dismiss();
                navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertEquals(2, navigation.currentPage());
                controller.recreate();
                activity = controller.get();
                navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                assertTrue(navigation instanceof ContinuousReaderNavigation);
                assertEquals(2, navigation.currentPage());
                navigation.goToPage(1, false);
                activity.findViewById(R.id.reader_settings).performClick();
                dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
                dialog.findViewById(R.id.reader_horizontal).performClick();
                dialog.dismiss();
                assertEquals(1, ((ViewPager2) activity.findViewById(R.id.container)).getCurrentItem());
                ReflectionHelpers.callInstanceMethod(activity, "setContinuousMode",
                    ReflectionHelpers.ClassParameter.from(boolean.class, true));
                ReflectionHelpers.callInstanceMethod(activity, "changeScrollTypeDialog");
                AlertDialog direction = (AlertDialog) ShadowDialog.getLatestDialog();
                direction.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.container).getVisibility());
                assertEquals(1, ((ViewPager2) activity.findViewById(R.id.container)).getCurrentItem());
            }
        }
    }

    @Test
    public void readerModePreferenceIsScopedPerGalleryWithGlobalFallback() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File continuousFolder = galleryFolder();
        File pagedFolder = galleryFolder();
        assertTrue(new File(continuousFolder, ".83101").createNewFile());
        assertTrue(new File(pagedFolder, ".83102").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", false).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> firstController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, continuousFolder)).setup()) {
                ZoomActivity first = firstController.get();
                GenericGallery firstGallery = ReflectionHelpers.getField(first, "gallery");
                assertTrue(firstGallery.getId() > 0);
                assertFalse((boolean) ReflectionHelpers.getField(first, "continuousMode"));
                ReflectionHelpers.callInstanceMethod(first, "setContinuousMode",
                    ReflectionHelpers.ClassParameter.from(boolean.class, true));
                assertTrue((boolean) ReflectionHelpers.getField(first, "continuousMode"));
                assertTrue(application.getSharedPreferences("Settings", 0)
                    .getBoolean("reader_mode_" + firstGallery.getId(), false));
            }
            try (ActivityController<ZoomActivity> secondController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, pagedFolder)).setup()) {
                ZoomActivity second = secondController.get();
                GenericGallery secondGallery = ReflectionHelpers.getField(second, "gallery");
                assertTrue(secondGallery.getId() > 0);
                assertFalse((boolean) ReflectionHelpers.getField(second, "continuousMode"));
                assertFalse(application.getSharedPreferences("Settings", 0)
                    .getBoolean("reader_mode_" + secondGallery.getId(), false));
            }
            try (ActivityController<ZoomActivity> restoredController = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, continuousFolder)).setup()) {
                assertTrue((boolean) ReflectionHelpers.getField(restoredController.get(), "continuousMode"));
            }
        }
    }

    @Test
    public void continuousRotationSurvivesRecreationAndDisclosesOriginalExport() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        application.getSharedPreferences("DebugDisplay", 0).edit().putBoolean("mask_images", false).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                layoutActivity(activity);
                RecyclerView list = activity.findViewById(R.id.continuous_reader);
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
                assertNotNull(holder);
                holder.itemView.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
                android.widget.ImageView image = holder.itemView.findViewById(R.id.continuous_page_image);
                image.setImageDrawable(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)));
                Menu menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                activity.onOptionsItemSelected(menu.findItem(R.id.rotate));
                ReaderPageRotations rotations = ReflectionHelpers.getField(activity, "continuousRotations");
                assertEquals(270, rotations.angle(0));
                assertEquals(0, rotations.angle(1));
                assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.continuous_page_status).getVisibility());

                controller.recreate();
                activity = controller.get();
                rotations = ReflectionHelpers.getField(activity, "continuousRotations");
                assertEquals(270, rotations.angle(0));
                layoutActivity(activity);
                list = activity.findViewById(R.id.continuous_reader);
                holder = list.findViewHolderForAdapterPosition(0);
                assertNotNull(holder);
                holder.itemView.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
                image = holder.itemView.findViewById(R.id.continuous_page_image);
                image.setImageDrawable(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)));
                menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                activity.onOptionsItemSelected(menu.findItem(R.id.share));
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(dialog.isShowing());
                assertEquals(activity.getString(R.string.reader_export_original_rotation),
                    ((android.widget.TextView) dialog.findViewById(android.R.id.message)).getText().toString());
                assertNull(ReflectionHelpers.getField(activity, "pageExportTask"));
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertNull(ReflectionHelpers.getField(activity, "pageExportTask"));
            }
        } finally {
            application.getSharedPreferences("DebugDisplay", 0).edit().clear().commit();
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void continuousSaveUsesSelectedSourceAfterPageChange() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".82129").createNewFile());
        try (FileOutputStream output = new FileOutputStream(new File(folder, "2.png"))) {
            assertTrue(Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output));
            output.write(new byte[]{7, 8, 9});
        }
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        application.getSharedPreferences("DebugDisplay", 0).edit().putBoolean("mask_images", false).commit();
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE);
        Global.initFromShared(application);
        File previousScreenFolder = Global.SCREENFOLDER;
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                Global.SCREENFOLDER = temporaryFolder.newFolder("screens");
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                layoutActivity(activity);
                RecyclerView list = activity.findViewById(R.id.continuous_reader);
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(1);
                assertNotNull(holder);
                holder.itemView.findViewById(R.id.continuous_page_status).setVisibility(View.VISIBLE);
                assertNull(ReflectionHelpers.callInstanceMethod(activity, "continuousPageSource",
                    ReflectionHelpers.ClassParameter.from(int.class, 1)));
                holder.itemView.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
                android.widget.ImageView image = holder.itemView.findViewById(R.id.continuous_page_image);
                image.setImageDrawable(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
                application.getSharedPreferences("DebugDisplay", 0).edit().putBoolean("mask_images", true).commit();
                assertNull(ReflectionHelpers.callInstanceMethod(activity, "continuousPageSource",
                    ReflectionHelpers.ClassParameter.from(int.class, 1)));
                application.getSharedPreferences("DebugDisplay", 0).edit().putBoolean("mask_images", false).commit();
                Menu menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                activity.onOptionsItemSelected(menu.findItem(R.id.save_page));
                Future<?> task = ReflectionHelpers.getField(activity, "pageExportTask");
                assertNotNull(task);
                navigation.goToPage(2, false);
                task.get(10, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                File saved = new File(Global.SCREENFOLDER, "82129-2.png");
                assertArrayEquals(Files.readAllBytes(new File(folder, "2.png").toPath()), Files.readAllBytes(saved.toPath()));
                assertFalse(new File(Global.SCREENFOLDER, "82129-3.png").exists());
                ReflectionHelpers.callInstanceMethod(activity, "exportContinuousPage",
                    ReflectionHelpers.ClassParameter.from(int.class, 1),
                    ReflectionHelpers.ClassParameter.from(Object.class, android.net.Uri.parse("https://example.invalid/page.png")),
                    ReflectionHelpers.ClassParameter.from(boolean.class, true),
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
                assertSame(task, ReflectionHelpers.getField(activity, "pageExportTask"));
                assertEquals(activity.getString(R.string.reader_page_unavailable), ShadowToast.getTextOfLatestToast());

                navigation.goToPage(1, false);
                layoutActivity(activity);
                holder = list.findViewHolderForAdapterPosition(1);
                assertNotNull(holder);
                holder.itemView.findViewById(R.id.continuous_page_status).setVisibility(View.GONE);
                image = holder.itemView.findViewById(R.id.continuous_page_image);
                image.setImageDrawable(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
                assertEquals(1, navigation.currentPage());
                ReflectionHelpers.setField(holder, "source", new PageFile(new File(folder, "1.png"), 1));
                assertNotNull(ReflectionHelpers.callInstanceMethod(activity, "continuousPageSource",
                    ReflectionHelpers.ClassParameter.from(int.class, 1)));
                activity.onOptionsItemSelected(menu.findItem(R.id.share));
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(dialog.isShowing());
                ReflectionHelpers.setField(holder, "source", new PageFile(new File(folder, "2.png"), 2));
                navigation.goToPage(2, false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Future<?> shareTask = ReflectionHelpers.getField(activity, "pageExportTask");
                assertTrue("Share did not start: " + ShadowToast.getTextOfLatestToast(), shareTask != task);
                task = shareTask;
                task.get(10, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Intent chooser = Shadows.shadowOf(activity).getNextStartedActivity();
                File[] sharedFiles = new File(application.getCacheDir(), "reader-share").listFiles();
                assertNotNull(sharedFiles);
                assertEquals(1, sharedFiles.length);
                assertArrayEquals(Files.readAllBytes(new File(folder, "1.png").toPath()),
                    Files.readAllBytes(sharedFiles[0].toPath()));
                // AndroidX FileProvider cannot resolve the Windows-hosted Robolectric cache path.
                if (File.separatorChar == '/') {
                    assertNotNull(chooser);
                    assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
                    Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
                    assertEquals("image/png", share.getType());
                    android.net.Uri shared = share.getParcelableExtra(Intent.EXTRA_STREAM);
                    try (java.io.InputStream stream = activity.getContentResolver().openInputStream(shared)) {
                        assertArrayEquals(Files.readAllBytes(new File(folder, "1.png").toPath()), stream.readAllBytes());
                    }
                }

                ReflectionHelpers.setField(activity, "pendingContinuousSave", 1);
                ReflectionHelpers.setField(activity, "pendingContinuousSource", new File(folder, "1.png"));
                controller.recreate();
                activity = controller.get();
                assertEquals(Integer.valueOf(1), ReflectionHelpers.getField(activity, "pendingContinuousSave"));
                activity.onRequestPermissionsResult(1, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    new int[]{PackageManager.PERMISSION_GRANTED});
                task = ReflectionHelpers.getField(activity, "pageExportTask");
                assertNotNull(task);
                task.get(10, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                saved = new File(Global.SCREENFOLDER, "82129-2.png");
                assertArrayEquals(Files.readAllBytes(new File(folder, "1.png").toPath()), Files.readAllBytes(saved.toPath()));
            }
        } finally {
            Global.SCREENFOLDER = previousScreenFolder;
            application.getSharedPreferences("DebugDisplay", 0).edit().clear().commit();
        }
    }

    @Test
    public void continuousViewportStaysClearOfVisibleControls() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                View content = activity.findViewById(android.R.id.content);
                content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                content.layout(0, 0, 1080, 1920);
                View viewport = activity.findViewById(R.id.continuous_reader);
                View toolbar = activity.findViewById(R.id.toolbar);
                View controls = activity.findViewById(R.id.master_layout);
                View pageSwitcher = activity.findViewById(R.id.page_switcher);
                assertTrue(viewport.getTop() >= toolbar.getBottom());
                assertTrue(viewport.getBottom() <= controls.getTop());
                int visibleHeight = viewport.getHeight();
                assertTrue(visibleHeight > 0);
                toolbar.setVisibility(View.GONE);
                pageSwitcher.setVisibility(View.GONE);
                content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
                content.layout(0, 0, 1080, 1920);
                assertTrue("hidden=" + viewport.getHeight() + ", visible=" + visibleHeight,
                    viewport.getHeight() > visibleHeight);
                toolbar.setVisibility(View.VISIBLE);
                pageSwitcher.setVisibility(View.VISIBLE);
                content.requestLayout();
                content.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
                content.layout(0, 0, 1920, 1080);
                assertTrue(viewport.getTop() >= toolbar.getBottom());
                assertTrue(viewport.getBottom() <= controls.getTop());
                assertTrue(viewport.getHeight() > 0);
            }
        }
    }

    @Test
    public void autoScrollControlIsLimitedToContinuousMode() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                View autoScroll = activity.findViewById(R.id.reader_auto_scroll);
                assertEquals(View.VISIBLE, autoScroll.getVisibility());
                assertTrue(autoScroll.hasOnClickListeners());
                assertTrue(autoScroll.performLongClick());
                assertNotNull(ShadowDialog.getLatestDialog());
                ShadowDialog.getLatestDialog().dismiss();

                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                ReflectionHelpers.callInstanceMethod(activity, "setContinuousMode",
                    ReflectionHelpers.ClassParameter.from(boolean.class, false),
                    ReflectionHelpers.ClassParameter.from(int.class, navigation.currentPage()));
                assertEquals(View.GONE, autoScroll.getVisibility());
            }
        }
    }

    @Test
    public void togglingReaderControlsKeepsSystemBarLayoutStable() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                int layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                int before = activity.getWindow().getDecorView().getSystemUiVisibility() & layoutFlags;
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.5f));
                int hidden = activity.getWindow().getDecorView().getSystemUiVisibility() & layoutFlags;
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.5f));
                int shown = activity.getWindow().getDecorView().getSystemUiVisibility() & layoutFlags;
                assertEquals(hidden, shown);
            }
        }
    }

    @Test
    public void continuousPageEdgeTapsFollowReaderSetting() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        Global.initFromShared(application);
        Global.setButtonChangePage(application, true);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.95f));
                assertEquals(2, navigation.currentPage());
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.05f));
                assertEquals(1, navigation.currentPage());
                Global.setButtonChangePage(application, false);
                boolean wasHidden = ReflectionHelpers.getField(activity, "isHidden");
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.95f));
                assertEquals(1, navigation.currentPage());
                assertEquals(!wasHidden, (boolean) ReflectionHelpers.getField(activity, "isHidden"));
            }
        }
    }

    @Test
    public void explicitNoActionTapModeLeavesPageAndControlsUnchanged() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true)
            .putInt("reader_tap_action", 2).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                boolean wasHidden = ReflectionHelpers.getField(activity, "isHidden");
                ReflectionHelpers.callInstanceMethod(activity, "handleReaderPageTap",
                    ReflectionHelpers.ClassParameter.from(float.class, 0.95f));
                assertEquals(1, navigation.currentPage());
                assertEquals(wasHidden, (boolean) ReflectionHelpers.getField(activity, "isHidden"));
            }
        }
    }

    @Test
    public void externalReaderKeysTurnPagesAndToggleControls() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
                assertEquals(2, navigation.currentPage());
                assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,
                    new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP, 1)));
                assertEquals(2, navigation.currentPage());
                assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_SPACE,
                    new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_SHIFT_ON)));
                assertEquals(1, navigation.currentPage());
                boolean wasHidden = ReflectionHelpers.getField(activity, "isHidden");
                assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_ENTER,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
                assertEquals(!wasHidden, (boolean) ReflectionHelpers.getField(activity, "isHidden"));
                assertTrue(activity.onKeyUp(KeyEvent.KEYCODE_ENTER,
                    new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)));
            }
        }
    }

    @Test
    public void externalLogicalKeysKeepPageOrderInRtl() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean(application.getString(R.string.preference_key_use_rtl), true)
            .putBoolean("instant_page_turn", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(
                ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ReaderNavigation navigation = ReflectionHelpers.getField(activity, "readerNavigation");
                navigation.goToPage(1, false);
                activity.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN));
                assertEquals(2, navigation.currentPage());
                activity.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(1, navigation.currentPage());
            }
        } finally {
            application.getSharedPreferences("Settings", 0).edit().clear().commit();
            Global.initFromShared(application);
        }
    }

    @Test
    public void pageActionsHandleMissingAndUnloadedFragments() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ZoomFragment existing = (ZoomFragment) activity.getSupportFragmentManager().findFragmentByTag("f0");
                if (existing != null) activity.getSupportFragmentManager().beginTransaction().remove(existing).commitNow();
                Menu menu = new MenuBuilder(activity);
                activity.getMenuInflater().inflate(R.menu.menu_zoom, menu);
                for (int id : new int[]{R.id.rotate, R.id.save_page, R.id.share}) {
                    activity.onOptionsItemSelected(menu.findItem(id));
                    assertEquals(activity.getString(R.string.reader_page_unavailable), ShadowToast.getTextOfLatestToast());
                }
                LocalGallery gallery = new LocalGallery(folder, true);
                ZoomFragment fragment = ZoomFragment.newInstance(gallery, 0, gallery.getGalleryFolder());
                activity.getSupportFragmentManager().beginTransaction().add(android.R.id.content, fragment, "f0").commitNow();
                PhotoView photo = preparePhoto(fragment);
                ReflectionHelpers.setField(fragment, "completedDownload", false);
                assertNull(ReflectionHelpers.callInstanceMethod(activity, "getAvailablePageImage"));
                ReflectionHelpers.setField(fragment, "completedDownload", true);
                assertSame(photo.getDrawable(), ReflectionHelpers.callInstanceMethod(activity, "getAvailablePageImage"));
                activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                assertNull(fragment.getDrawable());
                activity.onOptionsItemSelected(menu.findItem(R.id.rotate));
                assertEquals(activity.getString(R.string.reader_page_unavailable), ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test
    public void continuedOffsetSurvivesRecreationUntilPageChanges() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".82127").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear().commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Intent intent = readerIntent(application, folder);
            intent.putExtra(application.getPackageName() + ".PAGE", 2);
            intent.putExtra(application.getPackageName() + ".OFFSET", 0.4f);
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, intent).setup()) {
                controller.pause();
                ReadingPosition saved = loadProgress(82127);
                assertEquals(2, saved.page);
                assertEquals(0.4f, saved.offset, 0.001f);
                controller.resume();
                controller.recreate();
                controller.pause();
                saved = loadProgress(82127);
                assertEquals(2, saved.page);
                assertEquals(0.4f, saved.offset, 0.001f);
                controller.resume();
                ((ViewPager2) controller.get().findViewById(R.id.container)).setCurrentItem(2, false);
                saved = loadProgress(82127);
                assertEquals(3, saved.page);
                assertEquals(0f, saved.offset, 0f);
                ((ViewPager2) controller.get().findViewById(R.id.container)).setCurrentItem(1, false);
                saved = loadProgress(82127);
                assertEquals(2, saved.page);
                assertEquals(0f, saved.offset, 0f);
            }
        }
    }

    @Test
    public void continuousPositionSurvivesRecreationWhileImageIsLoading() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        assertTrue(new File(folder, ".82128").createNewFile());
        application.getSharedPreferences("Settings", 0).edit().clear()
            .putBoolean("reader_continuous_vertical", true).commit();
        Global.initFromShared(application);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            Intent intent = readerIntent(application, folder);
            intent.putExtra(application.getPackageName() + ".PAGE", 2);
            intent.putExtra(application.getPackageName() + ".OFFSET", 0.4f);
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, intent).setup()) {
                controller.pause();
                ReadingPosition position = loadProgress(82128);
                assertEquals(2, position.page);
                assertEquals(0.4f, position.offset, 0.001f);
                controller.resume().recreate().pause();
                position = loadProgress(82128);
                assertEquals(2, position.page);
                assertEquals(0.4f, position.offset, 0.001f);
                controller.resume();
                ReaderNavigation navigation = ReflectionHelpers.getField(controller.get(), "readerNavigation");
                navigation.goToPage(2, false);
                position = loadProgress(82128);
                assertEquals(3, position.page);
                assertEquals(0f, position.offset, 0f);
            }
        }
    }

    private ReadingPosition loadProgress(int id) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<ReadingPosition> position = new AtomicReference<>();
        ReadingProgress.load(id, result -> {
            position.set(result);
            finished.countDown();
        });
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertNotNull(position.get());
        return position.get();
    }

    @Test
    public void missingPageShowsRetryAndSuccessfulRetryClearsError() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        File folder = galleryFolder();
        Global.initFromShared(application);
        NetworkUtil.setType(NetworkUtil.ConnectionType.UNKNOWN);
        try (DatabaseHelper database = new DatabaseHelper(application)) {
            database.getWritableDatabase();
            try (ActivityController<ZoomActivity> controller = Robolectric.buildActivity(ZoomActivity.class, readerIntent(application, folder)).setup()) {
                ZoomActivity activity = controller.get();
                ZoomFragment fragment = ZoomFragment.newInstance(new LocalGallery(folder, true), 0, null);
                activity.getSupportFragmentManager().beginTransaction().add(android.R.id.content, fragment, "missing-page").commitNow();
                View root = fragment.requireView();
                View error = root.findViewById(R.id.reader_load_error);
                View loading = root.findViewById(R.id.reader_loading);
                assertEquals(View.VISIBLE, error.getVisibility());
                assertEquals(activity.getString(R.string.reader_page_load_failed),
                    ((android.widget.TextView) root.findViewById(R.id.reader_load_error_message)).getText().toString());
                assertEquals(View.GONE, loading.getVisibility());
                root.findViewById(R.id.reader_retry).performClick();
                assertEquals(View.VISIBLE, error.getVisibility());

                ReflectionHelpers.setField(fragment, "pageFile", new PageFile(new File(folder, "1.png"), 1));
                root.findViewById(R.id.reader_retry).performClick();
                ImageViewTarget<android.graphics.drawable.Drawable> target = ReflectionHelpers.getField(fragment, "target");
                assertNotNull(target.getRequest());
                target.onLoadStarted(null);
                assertEquals(View.GONE, error.getVisibility());
                assertEquals(View.VISIBLE, loading.getVisibility());
                target.onResourceReady(new BitmapDrawable(activity.getResources(),
                    Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), null);
                assertEquals(View.GONE, error.getVisibility());
                assertEquals(View.GONE, loading.getVisibility());
                target.onLoadFailed(null);
                assertEquals(View.VISIBLE, error.getVisibility());
                target.onLoadCleared(null);
                assertEquals(View.GONE, error.getVisibility());
                assertEquals(View.GONE, loading.getVisibility());
            }
        }
    }

    private void layoutActivity(ZoomActivity activity) {
        View content = activity.findViewById(android.R.id.content);
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 1080, 1920);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
