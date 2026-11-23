package com.theskyc.nreader;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CatalogRefreshRecoveryTest {
    @Test public void restoredObserverTracksTheSameQueuedWorkAndDismissalDoesNotCancelIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            new Configuration.Builder().setExecutor(executor).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        WorkManager manager = WorkManager.getInstance(context);
        CatalogRefreshViewModel original = new CatalogRefreshViewModel();
        CatalogRefreshViewModel restored = new CatalogRefreshViewModel();
        CatalogRefreshViewModel missing = new CatalogRefreshViewModel();
        try {
            original.start(context);
            UUID id = original.requestId();
            assertNotNull(id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            WorkInfo info;
            do {
                info = awaitFuture(manager.getWorkInfoById(id));
                if (info == null) Thread.sleep(10);
            } while (info == null && System.nanoTime() < deadline);
            assertNotNull(info);
            assertEquals(WorkInfo.State.ENQUEUED, info.getState());

            original.clearRequest();
            assertNull(original.requestId());
            assertEquals(WorkInfo.State.ENQUEUED, awaitFuture(manager.getWorkInfoById(id)).getState());
            restored.restore(context, id);
            assertEquals(id, restored.requestId());
            awaitFuture(manager.cancelWorkById(id).getResult());
            awaitStatus(restored, CatalogRefreshViewModel.Status.CANCELLED);
            assertEquals(CatalogRefreshViewModel.Status.QUEUED, original.status().getValue());

            missing.restore(context, UUID.randomUUID());
            awaitStatus(missing, CatalogRefreshViewModel.Status.FAILED);
        } finally {
            original.clearRequest();
            restored.clearRequest();
            missing.clearRequest();
            executor.shutdownNow();
        }
    }

    private static <T> T awaitFuture(ListenableFuture<T> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("WorkManager operation timed out", future.isDone());
        return future.get();
    }

    private static void awaitStatus(CatalogRefreshViewModel model, CatalogRefreshViewModel.Status expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (expected == model.status().getValue()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, model.status().getValue());
    }
}
