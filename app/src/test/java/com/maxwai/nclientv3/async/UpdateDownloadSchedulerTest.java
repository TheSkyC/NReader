// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.R;
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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kotlin.coroutines.EmptyCoroutineContext;

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
    public void periodicWorkerRepairsUserDownloadWhenAutomaticChecksAreDisabled() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_check_update);
        boolean hadValue = settings.contains(key);
        boolean previous = settings.getBoolean(key, true);
        UpdateDownloadRecord record = record();
        try {
            settings.edit().putBoolean(key, false).commit();
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.success(), checkWorker(true).doWork());
            assertNotNull(manager.getWorkInfoById(record.getId()).get(5L, TimeUnit.SECONDS));
            assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        } finally {
            manager.cancelWorkById(record.getId()).getResult().get(5L, TimeUnit.SECONDS);
            SharedPreferences.Editor editor = settings.edit();
            if (hadValue) editor.putBoolean(key, previous);
            else editor.remove(key);
            editor.commit();
        }
    }

    @Test
    public void periodicWorkerDoesNotRepairDownloadWhenSourceIsDisabled() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        String key = context.getString(R.string.preference_key_check_update);
        boolean hadValue = settings.contains(key);
        boolean previous = settings.getBoolean(key, true);
        UpdateDownloadRecord record = record();
        try {
            settings.edit().putBoolean(key, false).commit();
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.success(), checkWorker(false).doWork());
            assertEquals(null, manager.getWorkInfoById(record.getId()).get(5L, TimeUnit.SECONDS));
            assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        } finally {
            SharedPreferences.Editor editor = settings.edit();
            if (hadValue) editor.putBoolean(key, previous);
            else editor.remove(key);
            editor.commit();
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

    @Test
    public void staleCleanupLeavesAnotherCancellationIntact() throws Exception {
        UpdateDownloadRecord first = record();
        UpdateDownloadRecord second = record();
        File firstFile = new File(first.getFilePath());
        File secondFile = new File(second.getFilePath());
        assertTrue(firstFile.createNewFile());
        assertTrue(secondFile.createNewFile());
        try {
            store.recordCancellation(first);
            store.recordCancellation(second);
            UpdateDownloadScheduler.cleanupCancellation(context, first);
            assertTrue(!firstFile.exists());
            assertTrue(secondFile.exists());
            assertEquals(1, store.loadCancellations().size());
            assertEquals(second.getId(), store.loadCancellations().get(0).getId());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            firstFile.delete();
            //noinspection ResultOfMethodCallIgnored
            secondFile.delete();
        }
    }

    @Test
    public void cleanupRetainsDirectoryAndCancellationRecord() throws Exception {
        UpdateDownloadRecord record = record();
        File directory = new File(record.getFilePath());
        assertTrue(directory.mkdir());
        try {
            store.recordCancellation(record);
            UpdateDownloadScheduler.cleanupCancellation(context, record);
            assertTrue(directory.isDirectory());
            assertEquals(record.getId(), store.loadCancellations().get(0).getId());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
    }

    @Test
    public void delayedEnqueueSuccessKeepsTheCurrentAttempt() {
        UpdateDownloadRecord record = record();
        assertTrue(store.claimScheduledDownload(record, 1L));
        DeferredResult result = new DeferredResult();
        UpdateDownloadScheduler.awaitEnqueue(context, record.getId(), result, 0L, TimeUnit.MILLISECONDS, Runnable::run);
        assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        result.complete();
        assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        assertEquals(record.getId(), store.loadDownloadRecord().getId());
    }

    @Test
    public void delayedEnqueueFailureReportsRetryableError() {
        UpdateDownloadRecord record = record();
        assertTrue(store.claimScheduledDownload(record, 1L));
        DeferredResult result = new DeferredResult();
        UpdateDownloadScheduler.awaitEnqueue(context, record.getId(), result, 0L, TimeUnit.MILLISECONDS, Runnable::run);
        result.fail();
        assertEquals(UpdatePhase.ERROR, store.load().getPhase());
        assertEquals("interrupted_download", store.load().getErrorCode());
    }

    @Test
    public void delayedEnqueueFailureDoesNotInterruptRunningDownload() {
        UpdateDownloadRecord record = record();
        assertTrue(store.claimScheduledDownload(record, 1L));
        DeferredResult result = new DeferredResult();
        UpdateDownloadScheduler.awaitEnqueue(context, record.getId(), result, 0L, TimeUnit.MILLISECONDS, Runnable::run);
        store.save(UpdateState.downloading(record.getVersion(), record.getFilePath(), 1L, record.getSize(), 2L));
        result.fail();
        assertEquals(UpdatePhase.DOWNLOADING, store.load().getPhase());
        assertEquals(record.getId(), store.loadDownloadRecord().getId());
    }

    @Test
    public void delayedFailureFromOldEnqueueCannotOverwriteNewAttempt() {
        UpdateDownloadRecord first = record();
        assertTrue(store.claimScheduledDownload(first, 1L));
        DeferredResult result = new DeferredResult();
        UpdateDownloadScheduler.awaitEnqueue(context, first.getId(), result, 0L, TimeUnit.MILLISECONDS, Runnable::run);
        UpdateDownloadRecord second = record();
        assertTrue(store.claimScheduledDownload(second, 2L));
        result.fail();
        assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        assertEquals(second.getId(), store.loadDownloadRecord().getId());
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

    private UpdateCheckWorker checkWorker(boolean sourceEnabled) {
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context context, String name, WorkerParameters params) {
                    return null;
                }
            }, (context, id, data) -> Futures.immediateFuture(null),
            (context, id, info) -> Futures.immediateFuture(null));
        return new UpdateCheckWorker(context, parameters, () -> sourceEnabled);
    }

    private static final class DeferredResult implements ListenableFuture<Void> {
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        void complete() { future.complete(null); }
        void fail() { future.completeExceptionally(new IllegalStateException("fixture failure")); }

        @Override public void addListener(Runnable listener, Executor executor) {
            future.whenComplete((value, failure) -> executor.execute(listener));
        }

        @Override public boolean cancel(boolean interrupt) { return future.cancel(interrupt); }
        @Override public boolean isCancelled() { return future.isCancelled(); }
        @Override public boolean isDone() { return future.isDone(); }
        @Override public Void get() throws InterruptedException, ExecutionException { return future.get(); }
        @Override public Void get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }
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
