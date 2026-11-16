// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.NotificationSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 32)
public class UpdateNotificationTest {
    private Context context;
    private NotificationManager manager;
    private UpdateStateStore stateStore;

    @After
    public void tearDown() {
        stateStore.clear();
        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .remove(context.getString(R.string.preference_key_maximum_notification)).commit();
        NotificationSettings.initializeNotificationManager(context);
    }

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        context = application;
        stateStore = new UpdateStateStore(context);
        stateStore.clear();
        new UpdateMetadataStore(context).clearSelectedRelease();
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("UpdateReminder", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).edit().clear().commit();
        NotificationSettings.initializeNotificationManager(context);
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(Global.CHANNEL_ID4, "Updates", NotificationManager.IMPORTANCE_NONE));
    }

    @Test
    public void blockedChannelDoesNotConsumeAvailableReminder() {
        UpdateState state = UpdateState.available("1.1.0", 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);

        enableChannel();
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
        UpdateNotification.clear(context);
        UpdateNotification.publish(context, state);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void blockedChannelDoesNotConsumeReadyReminder() {
        UpdateState state = UpdateState.readyToInstall("1.1.0", "/update.apk", 3L, 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_ready"));
        assertEquals(0, manager.getActiveNotifications().length);

        enableChannel();
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
        UpdateNotification.clear(context);
        UpdateNotification.publish(context, state);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void missingChannelDoesNotConsumeReminder() {
        manager.deleteNotificationChannel(Global.CHANNEL_ID4);
        UpdateNotification.publish(context, UpdateState.available("1.1.0", 1L));
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void zeroNotificationLimitDoesNotConsumeReminder() {
        enableChannel();
        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .putInt(context.getString(R.string.preference_key_maximum_notification), 0).commit();
        NotificationSettings.initializeNotificationManager(context);
        UpdateState state = UpdateState.available("1.1.0", 1L);
        UpdateNotification.publish(context, state);
        assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_available"));
        assertEquals(0, manager.getActiveNotifications().length);

        context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
            .remove(context.getString(R.string.preference_key_maximum_notification)).commit();
        NotificationSettings.initializeNotificationManager(context);
        UpdateNotification.publish(context, state);
        assertEquals(1, manager.getActiveNotifications().length);
    }

    @Test
    public void noCandidateClearsOldNotificationAndAllowsReappearance() {
        enableChannel();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.saveSelectedRelease("2.0.0", 2L, "https://example.invalid/a.apk", "notes", false, "digest", 2L);
        stateStore.save(UpdateState.available("2.0.0", 1L));
        UpdateNotification.publish(context, stateStore.load());
        assertEquals(1, manager.getActiveNotifications().length);
        long generation = UpdateCheckCoordinator.begin();

        assertTrue(UpdateCheckWorker.completeCheckIfUnchanged(context, stateStore, metadata, stateStore.revision(), generation, null));
        assertEquals(UpdatePhase.IDLE, stateStore.load().getPhase());
        assertNull(metadata.getSelectedVersion());
        assertEquals(0, manager.getActiveNotifications().length);
        assertTrue(metadata.getLastSuccessfulCheck() > 0L);

        UpdateNotification.publish(context, UpdateState.available("2.0.0", 2L));
        assertEquals(1, manager.getActiveNotifications().length);
    }

    @Test
    public void noCandidatePreservesSkipPreference() {
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.saveSelectedRelease("2.0.0", 2L, "https://example.invalid/a.apk", "notes", false, "digest", 2L);
        stateStore.save(UpdateState.available("2.0.0", 1L));
        new UpdateReminderStore(context).skip("2.0.0");
        long generation = UpdateCheckCoordinator.begin();

        assertTrue(UpdateCheckWorker.completeCheckIfUnchanged(context, stateStore, metadata, stateStore.revision(), generation, null));
        assertTrue(new UpdateReminderStore(context).suppresses("2.0.0", System.currentTimeMillis()));
    }

    @Test
    public void skippedReplacementRemovesPreviousVersionNotification() {
        enableChannel();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.saveSelectedRelease("1.0.0", 1L, "https://example.invalid/a.apk", "notes", false, "digest", 2L);
        stateStore.save(UpdateState.available("1.0.0", 1L));
        UpdateNotification.publish(context, stateStore.load());
        assertEquals(1, manager.getActiveNotifications().length);
        new UpdateReminderStore(context).skip("2.0.0");
        VersionChecker.GitHubRelease replacement = new VersionChecker.GitHubRelease();
        replacement.versionCode = "2.0.0";
        replacement.downloadUrl = "https://example.invalid/b.apk";
        replacement.sha256 = "digest";

        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, stateStore, metadata, stateStore.revision(), replacement));
        assertEquals("2.0.0", stateStore.load().getVersion());
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void periodicWorkerRetriesReadyReminderWithAutomaticChecksDisabled() throws Exception {
        File apk = File.createTempFile("ready-update-", ".apk", context.getCacheDir());
        try {
            context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
                .putBoolean(context.getString(R.string.preference_key_check_update), false).commit();
            stateStore.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 3L, 1L));
            assertEquals(ListenableWorker.Result.success(), worker(true).doWork());
            assertEquals(0, manager.getActiveNotifications().length);
            enableChannel();
            assertEquals(ListenableWorker.Result.success(), worker(true).doWork());
            assertEquals(1, manager.getActiveNotifications().length);
            UpdateNotification.clear(context);
            assertEquals(ListenableWorker.Result.success(), worker(true).doWork());
            assertEquals(0, manager.getActiveNotifications().length);
        } finally {
            context.getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
                .remove(context.getString(R.string.preference_key_check_update)).commit();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    public void periodicWorkerWithDisabledSourceDoesNotNotify() throws Exception {
        File apk = File.createTempFile("ready-update-", ".apk", context.getCacheDir());
        try {
            enableChannel();
            stateStore.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 3L, 1L));
            assertEquals(ListenableWorker.Result.success(), worker(false).doWork());
            assertEquals(0, manager.getActiveNotifications().length);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    public void readyReminderRejectsStaleMissingAndInstalledUpdates() throws Exception {
        File apk = File.createTempFile("ready-update-", ".apk", context.getCacheDir());
        try {
            enableChannel();
            stateStore.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 3L, 1L));
            long staleRevision = stateStore.revision();
            stateStore.save(UpdateState.idle(2L));
            assertFalse(UpdateCheckWorker.publishReadyIfUnchanged(context, stateStore, new UpdateMetadataStore(context), staleRevision));
            stateStore.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath() + ".missing", 3L, 3L));
            UpdateCheckWorker.publishReadyIfUnchanged(context, stateStore, new UpdateMetadataStore(context), stateStore.revision());
            stateStore.save(UpdateState.readyToInstall("0.0.1", apk.getAbsolutePath(), 3L, 4L));
            UpdateCheckWorker.publishReadyIfUnchanged(context, stateStore, new UpdateMetadataStore(context), stateStore.revision());
            assertEquals(0, manager.getActiveNotifications().length);
            assertFalse(context.getSharedPreferences("UpdateNotification", Context.MODE_PRIVATE).contains("notified_ready"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    private UpdateCheckWorker worker(boolean sourceEnabled) {
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context context, String name, WorkerParameters params) {
                    return null;
                }
            }, (context, id, data) -> Futures.immediateFuture(null),
            (context, id, info) -> Futures.immediateFuture(null));
        return new UpdateCheckWorker(context, parameters, () -> sourceEnabled, () -> true);
    }

    private void enableChannel() {
        manager.getNotificationChannel(Global.CHANNEL_ID4).setImportance(NotificationManager.IMPORTANCE_DEFAULT);
    }
}
