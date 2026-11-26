package com.theskyc.nreader.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;

import androidx.recyclerview.widget.RecyclerView;

import com.theskyc.nreader.LocalActivity;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.api.local.LocalSortType;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.downloader.GalleryDownloaderV2;
import com.theskyc.nreader.async.downloader.DownloadObserver;
import com.theskyc.nreader.settings.Global;

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

    private List<Object> merge(LocalAdapter adapter, LocalGallery gallery, GalleryDownloaderV2... tasks) {
        return ReflectionHelpers.callInstanceMethod(adapter, "createHash",
            ReflectionHelpers.ClassParameter.from(List.class, Arrays.asList(tasks)),
            ReflectionHelpers.ClassParameter.from(List.class, Collections.singletonList(gallery)));
    }
}
