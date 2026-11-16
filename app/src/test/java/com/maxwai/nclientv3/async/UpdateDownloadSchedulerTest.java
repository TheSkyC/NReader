// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdateDownloadRecord;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class UpdateDownloadSchedulerTest {
    private Context context;
    private UpdateStateStore store;
    private WorkManager manager;
    private ExecutorService executor;
    private File previousFolder;
    private File folder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        executor = Executors.newFixedThreadPool(3);
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            new Configuration.Builder().setExecutor(executor).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        manager = WorkManager.getInstance(context);
        store = new UpdateStateStore(context);
        store.clear();
        previousFolder = Global.UPDATEFOLDER;
        folder = new File(context.getCacheDir(), "scheduler-test-" + UUID.randomUUID());
        assertTrue(folder.mkdir());
        Global.UPDATEFOLDER = folder;
    }

    @After
    public void tearDown() {
        store.clear();
        Global.UPDATEFOLDER = previousFolder;
        executor.shutdownNow();
        //noinspection ResultOfMethodCallIgnored
        folder.delete();
    }

    @Test
    public void startupRepairsMissingEnqueueWithoutDeletingPartialFile() throws Exception {
        UpdateDownloadRecord record = record();
        File partial = new File(record.getFilePath());
        assertTrue(partial.createNewFile());
        try {
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(UpdatePhase.QUEUED, UpdateStateRecovery.reconcileStored(context).getPhase());
            UpdateStateRecovery.reconcileStored(context);
            WorkInfo info = awaitWork(record.getId());
            assertNotNull(info);
            assertTrue(partial.isFile());
            assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
            List<WorkInfo> work = manager.getWorkInfosForUniqueWork("nreader_update_download_" + record.getId())
                .get(5L, TimeUnit.SECONDS);
            assertEquals(1, work.size());
            assertEquals(record.getId(), work.get(0).getId());
        } finally {
            manager.cancelWorkById(record.getId()).getResult().get(5L, TimeUnit.SECONDS);
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    @Test
    public void canceledSystemWorkBecomesRetryableError() throws Exception {
        UpdateDownloadRecord record = record();
        assertTrue(store.claimScheduledDownload(record, 1L));
        UpdateStateRecovery.reconcileStored(context);
        assertNotNull(awaitWork(record.getId()));
        manager.cancelWorkById(record.getId()).getResult().get(5L, TimeUnit.SECONDS);
        UpdateStateRecovery.reconcileStored(context);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.load().getPhase() != UpdatePhase.ERROR && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(UpdatePhase.ERROR, store.load().getPhase());
        assertEquals("interrupted_download", store.load().getErrorCode());
    }

    @Test
    public void cancelQueuedTaskRemovesItsPartialAfterSystemCancellation() throws Exception {
        UpdateDownloadRecord record = record();
        File partial = new File(record.getFilePath());
        assertTrue(partial.createNewFile());
        try {
            assertTrue(store.claimScheduledDownload(record, 1L));
            UpdateStateRecovery.reconcileStored(context);
            assertNotNull(awaitWork(record.getId()));
            UpdateDownloadManager.cancel(context);
            assertEquals(UpdatePhase.AVAILABLE, store.load().getPhase());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (partial.exists() && System.nanoTime() < deadline) Thread.sleep(10L);
            assertTrue(!partial.exists());
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(record.getId()).get(5L, TimeUnit.SECONDS).getState());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    private WorkInfo awaitWork(UUID id) throws Exception {
        WorkInfo info = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (info == null && System.nanoTime() < deadline) {
            info = manager.getWorkInfoById(id).get(2L, TimeUnit.SECONDS);
            if (info == null) Thread.sleep(10L);
        }
        return info;
    }

    private UpdateDownloadRecord record() {
        UUID id = UUID.randomUUID();
        String flavor = BuildConfig.FLAVOR.equals("minApi26") ? "minApi26" : "minApi28";
        File partial = new File(folder, "NReader-99.0.0-" + flavor + "-" + id + ".apk.part");
        store.save(UpdateState.available("99.0.0", 1L));
        return new UpdateDownloadRecord(id, "99.0.0", partial.getAbsolutePath(), "https://example.invalid/update.apk",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3L);
    }
}
