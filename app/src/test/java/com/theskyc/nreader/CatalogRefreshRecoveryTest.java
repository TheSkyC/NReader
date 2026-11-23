package com.theskyc.nreader;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.google.common.util.concurrent.ListenableFuture;
import com.theskyc.nreader.async.database.DatabaseHelper;
import com.theskyc.nreader.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

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
    @Test public void settingsRestoresTheSavedRequestAndDisplaysItsLaterStatus() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Global.initFromShared(context);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            new Configuration.Builder().setExecutor(executor).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        WorkManager manager = WorkManager.getInstance(context);
        try (DatabaseHelper database = new DatabaseHelper(context)) {
            database.getWritableDatabase();
            Bundle saved = new Bundle();
            UUID id;
            try (ActivityController<SettingsActivity> controller =
                     Robolectric.buildActivity(SettingsActivity.class).setup()) {
                SettingsActivity activity = controller.get();
                CatalogRefreshViewModel model = ReflectionHelpers.getField(activity, "catalogRefresh");
                ReflectionHelpers.callInstanceMethod(activity, "showCatalogCheckDialog");
                model.start(context);
                id = model.requestId();
                assertEquals(WorkInfo.State.ENQUEUED, awaitWork(manager, id).getState());
                activity.onSaveInstanceState(saved);
                assertEquals(id.toString(), saved.getString("catalog_check_id"));
            }
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)
                     .create(saved).start().resume().visible()) {
                SettingsActivity activity = controller.get();
                CatalogRefreshViewModel model = ReflectionHelpers.getField(activity, "catalogRefresh");
                assertEquals(id, model.requestId());
                awaitFuture(manager.cancelWorkById(id).getResult());
                awaitStatus(model, CatalogRefreshViewModel.Status.CANCELLED);
                AlertDialog dialog = ReflectionHelpers.getField(activity, "catalogCheckDialog");
                TextView message = dialog.findViewById(android.R.id.message);
                assertNotNull(message);
                assertEquals(activity.getString(R.string.tag_catalog_check_cancelled), message.getText().toString());
                dialog.dismiss();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Bundle dismissed = new Bundle();
                activity.onSaveInstanceState(dismissed);
                assertNull(dismissed.getString("catalog_check_id"));
            }
        } finally {
            executor.shutdownNow();
        }
    }

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

            original.start(context);
            UUID replacedId = original.requestId();
            awaitWork(manager, replacedId);
            restored.clearRequest();
            restored.restore(context, replacedId);
            original.start(context);
            UUID replacementId = original.requestId();
            assertTrue(!replacedId.equals(replacementId));
            assertEquals(WorkInfo.State.ENQUEUED, awaitWork(manager, replacementId).getState());
            awaitReplacedStatus(restored);
            awaitStatus(original, CatalogRefreshViewModel.Status.QUEUED);
            assertEquals(replacementId, original.requestId());
            WorkInfo replaced = awaitFuture(manager.getWorkInfoById(replacedId));
            assertTrue(replaced == null || replaced.getState() == WorkInfo.State.CANCELLED);
            awaitFuture(manager.cancelWorkById(replacementId).getResult());
            awaitStatus(original, CatalogRefreshViewModel.Status.CANCELLED);
        } finally {
            original.clearRequest();
            restored.clearRequest();
            missing.clearRequest();
            executor.shutdownNow();
        }
    }

    private static void awaitReplacedStatus(CatalogRefreshViewModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            CatalogRefreshViewModel.Status current = model.status().getValue();
            if (current == CatalogRefreshViewModel.Status.CANCELLED
                || current == CatalogRefreshViewModel.Status.FAILED) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Replaced work did not finish: " + model.status().getValue());
    }

    private static WorkInfo awaitWork(WorkManager manager, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        WorkInfo info;
        do {
            info = awaitFuture(manager.getWorkInfoById(id));
            if (info == null) Thread.sleep(10);
        } while (info == null && System.nanoTime() < deadline);
        assertNotNull(info);
        return info;
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
