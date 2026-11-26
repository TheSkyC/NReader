package com.theskyc.nreader.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;

import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog;

import com.theskyc.nreader.LocalActivity;
import com.theskyc.nreader.R;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.api.local.LocalSortType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.downloader.GalleryDownloaderV2;
import com.theskyc.nreader.async.downloader.DownloadObserver;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.components.classes.MultichoiceAdapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocalAdapterDownloadMergeTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private DatabaseHelper database;

    @Before public void openDatabase() {
        database = new DatabaseHelper(RuntimeEnvironment.getApplication());
        database.getWritableDatabase();
    }

    @After public void closeDatabase() {
        database.close();
        Queries.setDb(null);
    }

    @Test public void downloadReplacesOnlyTheGalleryInItsActualTargetFolder() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            File target = temporaryFolder.newFolder("sanitized.DUP1");
            File unrelated = temporaryFolder.newFolder("original title");
            LocalGallery downloading = new LocalGallery(target, true);
            LocalGallery sameTitle = new LocalGallery(unrelated, true);
            GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "original title", null, 100);
            ReflectionHelpers.setField(task, "folder", target);

            List<Object> items = ReflectionHelpers.callInstanceMethod(adapter, "createHash",
                ReflectionHelpers.ClassParameter.from(List.class, Collections.singletonList(task)),
                ReflectionHelpers.ClassParameter.from(List.class, Arrays.asList(downloading, sameTitle)));

            assertEquals(2, items.size());
            assertTrue(items.contains(task));
            assertTrue(items.contains(sameTitle));
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void repairTaskUsesItsTargetBeforeDownloadStarts() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            File target = temporaryFolder.newFolder("repair-target");
            LocalGallery gallery = new LocalGallery(target, true);
            GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "renamed", null, 101);
            task.configureRepair(target, Collections.singletonList(1));

            List<Object> items = ReflectionHelpers.callInstanceMethod(adapter, "createHash",
                ReflectionHelpers.ClassParameter.from(List.class, Collections.singletonList(task)),
                ReflectionHelpers.ClassParameter.from(List.class, Collections.singletonList(gallery)));

            assertEquals(Collections.singletonList(task), items);
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void startingTaskMergesFolderAssignedAfterListWasBuilt() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("pending-target");
        LocalGallery gallery = new LocalGallery(target, true);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(gallery)));
        try {
            GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "pending-target", null, 104);
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            assertEquals(2, adapter.getItemCount());

            ReflectionHelpers.setField(task, "folder", target);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerStartDownload(task);

            assertEquals(1, adapter.getItemCount());
            assertEquals(task, adapter.getItemAt(0));
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void startingTaskDefersMergeUntilSelectionEnds() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("selected-target");
        LocalGallery gallery = new LocalGallery(target, true);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(gallery)));
        try {
            GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "selected-target", null, 105);
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            assertEquals(2, adapter.getSelected().size());

            ReflectionHelpers.setField(task, "folder", target);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerStartDownload(task);
            assertEquals(2, adapter.getItemCount());
            assertEquals(2, adapter.getSelected().size());

            adapter.deselectAll();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, adapter.getItemCount());
            assertEquals(task, adapter.getItemAt(0));
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void deselectingLastItemCompletesBeforeDeferredMerge() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("last-selection");
        LocalGallery gallery = new LocalGallery(target, true);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(gallery)));
        try {
            GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "last-selection", null, 106);
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            ReflectionHelpers.setField(task, "folder", target);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerStartDownload(task);

            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onItemRangeChanged(int positionStart, int itemCount) {
                    assertTrue(positionStart + itemCount <= adapter.getItemCount());
                }
            });
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, 0));
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, 1));
            assertEquals(2, adapter.getItemCount());
            adapter.selectAll();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, adapter.getItemCount());
            assertEquals(2, adapter.getSelected().size());
            adapter.deselectAll();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, adapter.getItemCount());
            assertEquals(task, adapter.getItemAt(0));
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void taskPinningRespectsTheSelectedSortWithinEachGroup() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        LocalSortType original = Global.getLocalSortType();
        boolean originalPin = Global.isLocalPinDownloads();
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            LocalGallery gallery = new LocalGallery(temporaryFolder.newFolder("alpha"), true);
            GalleryDownloaderV2 later = new GalleryDownloaderV2(activity, "zeta", null, 102);
            GalleryDownloaderV2 earlier = new GalleryDownloaderV2(activity, "beta", null, 103);
            Global.setLocalSortType(activity, new LocalSortType(LocalSortType.Type.TITLE, false));
            Global.setLocalPinDownloads(activity, false);
            assertEquals(Arrays.asList(gallery, earlier, later), merge(adapter, gallery, later, earlier));
            Global.setLocalPinDownloads(activity, true);
            assertEquals(Arrays.asList(earlier, later, gallery), merge(adapter, gallery, later, earlier));
            Global.setLocalSortType(activity, new LocalSortType(LocalSortType.Type.TITLE, true));
            assertEquals(Arrays.asList(later, earlier, gallery), merge(adapter, gallery, later, earlier));
        } finally {
            adapter.removeObserver();
            Global.setLocalSortType(activity, original);
            Global.setLocalPinDownloads(activity, originalPin);
        }
    }

    @Test public void completedSelectedTaskBecomesSelectedGallery() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("completed-selection");
        assertTrue(new File(target, "1.jpg").createNewFile());
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "completed-selection", null, 107);
        ReflectionHelpers.setField(task, "folder", target);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.FINISHED);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerEndDownload(task);

            assertEquals(1, adapter.getItemCount());
            Object item = adapter.getItemAt(0);
            assertTrue(item instanceof LocalGallery);
            assertEquals(target, ((LocalGallery) item).getDirectory());
            assertEquals(Collections.singletonList(item), new java.util.ArrayList<>(adapter.getSelected()));
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, 0));
            assertEquals(MultichoiceAdapter.Mode.NORMAL, adapter.getMode());
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void canceledTaskDoesNotMoveSelectionToAnotherGallery() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        LocalSortType original = Global.getLocalSortType();
        boolean originalPin = Global.isLocalPinDownloads();
        Global.setLocalSortType(activity, new LocalSortType(LocalSortType.Type.TITLE, false));
        Global.setLocalPinDownloads(activity, false);
        LocalGallery gallery = new LocalGallery(temporaryFolder.newFolder("z-selected"), true);
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "a-canceled", null, 108);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(gallery)));
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            assertEquals(gallery, adapter.getItemAt(1));
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, 1));
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.CANCELED);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerCancelDownload(task);

            assertEquals(1, adapter.getItemCount());
            assertEquals(Collections.singletonList(gallery), new java.util.ArrayList<>(adapter.getSelected()));
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, 0));
            assertTrue(adapter.getSelected().isEmpty());
            assertEquals(MultichoiceAdapter.Mode.NORMAL, adapter.getMode());
        } finally {
            adapter.removeObserver();
            Global.setLocalSortType(activity, original);
            Global.setLocalPinDownloads(activity, originalPin);
        }
    }

    @Test public void canceledSelectedTaskWithoutRetainedPagesLeavesSelectionMode() {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "canceled-selection", null, 109);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.CANCELED);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerCancelDownload(task);

            assertEquals(0, adapter.getItemCount());
            assertFalse(adapter.getSelected().contains(task));
            assertEquals(MultichoiceAdapter.Mode.NORMAL, adapter.getMode());
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void canceledSelectedTaskWithRetainedPagesBecomesSelectedGallery() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("retained-selection");
        assertTrue(new File(target, "1.jpg").createNewFile());
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "retained-selection", null, 110);
        ReflectionHelpers.setField(task, "folder", target);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>());
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.CANCELED);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerCancelDownload(task);

            assertEquals(1, adapter.getItemCount());
            Object item = adapter.getItemAt(0);
            assertTrue(item instanceof LocalGallery);
            assertEquals(target, ((LocalGallery) item).getDirectory());
            assertEquals(Collections.singletonList(item), new java.util.ArrayList<>(adapter.getSelected()));
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void canceledTaskDropsDeletedFolderEvenWhenMergeWasDeferred() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("deleted-pending-merge");
        LocalGallery oldFolder = new LocalGallery(target, true);
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "deleted-pending-merge", null, 112);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(oldFolder)));
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            adapter.selectAll();
            ReflectionHelpers.setField(task, "folder", target);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerStartDownload(task);
            assertTrue(target.delete());
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.CANCELED);
            observer.triggerCancelDownload(task);

            assertEquals(0, adapter.getItemCount());
            assertTrue(adapter.getSelected().isEmpty());
            assertEquals(MultichoiceAdapter.Mode.NORMAL, adapter.getMode());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, adapter.getItemCount());
        } finally {
            adapter.removeObserver();
        }
    }

    @Test public void completingDuringDeleteConfirmationDeletesOnlySelectedGallery() throws Exception {
        LocalActivity activity = Robolectric.buildActivity(LocalActivity.class).get();
        activity.setTheme(R.style.AppTheme_Material3);
        Global.initFromShared(activity);
        File target = temporaryFolder.newFolder("delete-completed");
        File survivor = temporaryFolder.newFolder("delete-survivor");
        assertTrue(new File(target, "1.jpg").createNewFile());
        assertTrue(new File(survivor, "1.jpg").createNewFile());
        GalleryDownloaderV2 task = new GalleryDownloaderV2(activity, "delete-completed", null, 111);
        ReflectionHelpers.setField(task, "folder", target);
        LocalGallery other = new LocalGallery(survivor, true);
        LocalAdapter adapter = new LocalAdapter(activity, new java.util.ArrayList<>(Collections.singletonList(other)));
        try {
            ReflectionHelpers.setField(adapter, "galleryDownloaders", new java.util.ArrayList<>(Collections.singletonList(task)));
            ReflectionHelpers.callInstanceMethod(adapter, "sortElements");
            int taskPosition = adapter.getItemAt(0) == task ? 0 : 1;
            ReflectionHelpers.callInstanceMethod(adapter, "toggleSelection",
                ReflectionHelpers.ClassParameter.from(int.class, taskPosition));
            adapter.deleteSelected();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ReflectionHelpers.setField(task, "status", GalleryDownloaderV2.Status.FINISHED);
            DownloadObserver observer = ReflectionHelpers.getField(adapter, "observer");
            observer.triggerEndDownload(task);
            assertEquals(1, adapter.getSelected().size());
            assertTrue(adapter.getSelected().iterator().next() instanceof LocalGallery);
            assertEquals(target, ((LocalGallery) adapter.getSelected().iterator().next()).getDirectory());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse(target.exists());
            assertTrue(survivor.exists());
            assertTrue(adapter.getSelected().isEmpty());
            assertEquals(MultichoiceAdapter.Mode.NORMAL, adapter.getMode());
            assertEquals(1, adapter.getItemCount());
            assertEquals(other, adapter.getItemAt(0));
        } finally {
            adapter.removeObserver();
        }
    }

    private List<Object> merge(LocalAdapter adapter, LocalGallery gallery, GalleryDownloaderV2... tasks) {
        return ReflectionHelpers.callInstanceMethod(adapter, "createHash",
            ReflectionHelpers.ClassParameter.from(List.class, Arrays.asList(tasks)),
            ReflectionHelpers.ClassParameter.from(List.class, Collections.singletonList(gallery)));
    }
}
