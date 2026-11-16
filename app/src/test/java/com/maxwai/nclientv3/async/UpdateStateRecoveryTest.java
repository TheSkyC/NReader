// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.async.update.UpdateDownloadRecord;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.components.activities.CrashApplication;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.NotificationSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
public class UpdateStateRecoveryTest {
    private Context context;
    private File previousFolder;
    private File updateFolder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        previousFolder = Global.UPDATEFOLDER;
        updateFolder = new File(context.getCacheDir(), "update-recovery-test-" + UUID.randomUUID());
        assertTrue(updateFolder.mkdir());
        Global.UPDATEFOLDER = updateFolder;
    }

    @After
    public void tearDown() {
        Global.UPDATEFOLDER = previousFolder;
        //noinspection ResultOfMethodCallIgnored
        updateFolder.delete();
    }

    @Test
    public void completedInstallIsClearedWhenRunningVersionIsNewer() {
        UpdateState state = UpdateState.installing("0.0.0", "/missing.apk", 1L);
        assertEquals(UpdatePhase.IDLE, UpdateStateRecovery.reconcile(context, state).getPhase());
    }

    @Test
    public void completedInstallRemovesOnlyTheRecordedOwnedArtifact() throws IOException {
        File file = createFile(updateFolder, "NReader-0.0.0-minApi28-release.apk");
        try {
            UpdateStateRecovery.reconcile(context, UpdateState.installing("0.0.0", file.getAbsolutePath(), 1L));
            assertFalse(file.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void installerCancellationReturnsToReadyWhenFileRemains() throws IOException {
        File file = createFile(updateFolder, "pending.apk");
        try {
            UpdateState state = UpdateState.installing("99.0.0", file.getAbsolutePath(), 1L);
            assertEquals(UpdatePhase.READY_TO_INSTALL, UpdateStateRecovery.reconcile(context, state).getPhase());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void interruptedDownloadRemovesOwnedTemporaryFile() throws IOException {
        File file = createFile(updateFolder, "NReader-99.0.0-minApi28-12345678-1234-1234-1234-123456789abc.apk.part");
        try {
            UpdateState state = UpdateState.downloading("99.0.0", file.getAbsolutePath(), 10L, 100L, 1L);
            assertEquals(UpdatePhase.ERROR, UpdateStateRecovery.reconcile(context, state).getPhase());
            assertFalse(file.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void storedDownloadWithoutAnActiveOwnerIsRecoveredAsInterrupted() throws IOException {
        UpdateStateStore store = new UpdateStateStore(context);
        File file = createFile(updateFolder, "NReader-99.0.0-minApi28-release.apk.part");
        try {
            store.save(UpdateState.downloading("99.0.0", file.getAbsolutePath(), 10L, 100L, 1L));
            UpdateState recovered = UpdateStateRecovery.reconcileStored(context);
            assertEquals(UpdatePhase.ERROR, recovered.getPhase());
            assertEquals("interrupted_download", store.load().getErrorCode());
            assertFalse(file.exists());
        } finally {
            store.clear();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void queuedStateWithoutSchedulerIdentityPreservesFileAndReportsInterruption() throws IOException {
        File file = createFile(updateFolder, "NReader-99.0.0-minApi28-release.apk.part");
        try {
            UpdateState state = UpdateState.queued("99.0.0", file.getAbsolutePath(), 100L, 1L);
            UpdateState recovered = UpdateStateRecovery.reconcile(context, state);
            assertEquals(UpdatePhase.ERROR, recovered.getPhase());
            assertEquals("interrupted_download", recovered.getErrorCode());
            assertTrue(file.isFile());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void persistedCancellationIntentStopsQueuedWorkAndCleansItsPartial() throws IOException {
        UpdateStateStore store = new UpdateStateStore(context);
        UUID id = UUID.randomUUID();
        String flavor = BuildConfig.FLAVOR.equals("minApi26") ? "minApi26" : "minApi28";
        File file = createFile(updateFolder, "NReader-99.0.0-" + flavor + "-" + id + ".apk.part");
        UpdateDownloadRecord record = new UpdateDownloadRecord(id, "99.0.0", file.getAbsolutePath(),
            "https://example.invalid/update.apk",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3L);
        try {
            store.save(UpdateState.available(record.getVersion(), 1L));
            assertTrue(store.claimScheduledDownload(record, 2L));
            store.recordCancellation(record);

            UpdateState recovered = UpdateStateRecovery.reconcileStored(context);

            assertEquals(UpdatePhase.AVAILABLE, recovered.getPhase());
            assertFalse(file.exists());
            assertTrue(store.loadCancellation() == null);
        } finally {
            store.clear();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void interruptedDownloadClearsRetainedProgressNotification() {
        NotificationSettings.initializeNotificationManager(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(1201, new Notification.Builder(context, Global.CHANNEL_ID4)
            .setSmallIcon(android.R.drawable.stat_sys_download).build());
        assertEquals(1, manager.getActiveNotifications().length);
        UpdateStateRecovery.reconcile(context, UpdateState.downloading("99.0.0", "/missing.apk.part", 1L, 2L, 1L));
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void sameVersionStartupRecoversInterruptedDownloadAndClearsNotification() {
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        boolean hadLastVersion = preferences.contains("last_version");
        String lastVersion = preferences.getString("last_version", null);
        UpdateStateStore store = new UpdateStateStore(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            preferences.edit().putString("last_version", BuildConfig.VERSION_NAME).commit();
            store.save(UpdateState.downloading("99.0.0", "/missing.apk.part", 1L, 2L, 1L));
            manager.notify(1201, new Notification.Builder(context, Global.CHANNEL_ID4)
                .setSmallIcon(android.R.drawable.stat_sys_download).build());
            ((CrashApplication) context).onCreate();
            assertEquals("interrupted_download", store.load().getErrorCode());
            assertEquals(0, manager.getActiveNotifications().length);
        } finally {
            store.clear();
            SharedPreferences.Editor editor = preferences.edit();
            if (hadLastVersion) editor.putString("last_version", lastVersion);
            else editor.remove("last_version");
            editor.commit();
        }
    }

    @Test
    public void matchingNewerVersionCodeTakesPrecedenceOverVersionName() throws IOException {
        File file = createFile(updateFolder, "NReader-0.0.0-minApi28-release.apk");
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        try {
            metadata.saveSelectedRelease("0.0.0", (long) BuildConfig.VERSION_CODE + 1L,
                "https://example.invalid/update.apk", null, false, null, 1L);
            UpdateState state = UpdateState.installing("0.0.0", file.getAbsolutePath(), 1L);
            assertEquals(UpdatePhase.READY_TO_INSTALL, UpdateStateRecovery.reconcile(context, state).getPhase());
            assertTrue(file.isFile());
        } finally {
            metadata.clearSelectedRelease();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void recoveryPreservesUnrelatedAndOutsideFiles() throws IOException {
        File unrelated = createFile(updateFolder, "notes.part");
        File outside = createFile(context.getCacheDir(), "NReader-99.0.0-minApi28-12345678-1234-1234-1234-123456789abc.apk.part");
        File nestedFolder = new File(updateFolder, "nested");
        assertTrue(nestedFolder.mkdir());
        File nested = createFile(nestedFolder, "NReader-99.0.0-minApi28-release.apk.part");
        try {
            for (File file : new File[] {unrelated, outside, nested}) {
                UpdateState state = UpdateState.downloading("99.0.0", file.getAbsolutePath(), 1L, 2L, 1L);
                assertEquals(UpdatePhase.ERROR, UpdateStateRecovery.reconcile(context, state).getPhase());
                assertTrue(file.isFile());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            unrelated.delete();
            //noinspection ResultOfMethodCallIgnored
            outside.delete();
            //noinspection ResultOfMethodCallIgnored
            nested.delete();
            //noinspection ResultOfMethodCallIgnored
            nestedFolder.delete();
        }
    }

    @Test
    public void versionChangeDoesNotDeleteOtherFilesOrDirectories() throws Exception {
        File artifact = createFile(updateFolder, "NReader-0.0.0-minApi28-release.apk");
        File unrelated = createFile(updateFolder, "keep.txt");
        File nestedFolder = new File(updateFolder, "keep-directory");
        assertTrue(nestedFolder.mkdir());
        File nested = createFile(nestedFolder, "keep.txt");
        UpdateStateStore store = new UpdateStateStore(context);
        SharedPreferences preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
        boolean hadLastVersion = preferences.contains("last_version");
        String lastVersion = preferences.getString("last_version", null);
        try {
            store.save(UpdateState.installing("0.0.0", artifact.getAbsolutePath(), 1L));
            Method method = CrashApplication.class.getDeclaredMethod("afterUpdateChecks", SharedPreferences.class, String.class);
            method.setAccessible(true);
            method.invoke(context, preferences, "0.9.0");
            UpdateStateRecovery.reconcileStored(context);
            assertFalse(artifact.exists());
            assertTrue(unrelated.isFile());
            assertTrue(nested.isFile());
            assertEquals(UpdatePhase.IDLE, store.load().getPhase());
        } finally {
            store.clear();
            SharedPreferences.Editor editor = preferences.edit();
            if (hadLastVersion) editor.putString("last_version", lastVersion);
            else editor.remove("last_version");
            editor.commit();
            //noinspection ResultOfMethodCallIgnored
            artifact.delete();
            //noinspection ResultOfMethodCallIgnored
            unrelated.delete();
            //noinspection ResultOfMethodCallIgnored
            nested.delete();
            //noinspection ResultOfMethodCallIgnored
            nestedFolder.delete();
        }
    }

    private static File createFile(File folder, String name) throws IOException {
        File file = new File(folder, name);
        if (!file.createNewFile()) throw new IOException("Unable to create test file");
        return file;
    }
}
