// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.theskyc.nreader.R;
import com.theskyc.nreader.UpdateCenterActivity;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;
import com.theskyc.nreader.settings.NotificationSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class UpdateCheckWorkerPublicationTest {
    private Context context;
    private UpdateStateStore state;
    private UpdateMetadataStore metadata;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        state = new UpdateStateStore(context);
        metadata = new UpdateMetadataStore(context);
        state.clear();
        metadata.clearSelectedRelease();
    }

    @After
    public void tearDown() {
        state.clear();
        metadata.clearSelectedRelease();
    }

    @Test
    public void idlePublicationStoresSelectedAsset() {
        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), release("2.0.0")));
        assertEquals(UpdatePhase.AVAILABLE, state.load().getPhase());
        assertEquals("2.0.0", metadata.getSelectedVersion());
    }

    @Test
    public void availablePublicationReplacesDifferentVersionTogetherWithAsset() {
        metadata.saveSelectedRelease("1.1.0", 11L, "https://example.invalid/old.apk", "old", false, "old-digest", 42L);
        state.save(UpdateState.available("1.1.0", 1L));

        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), release("2.0.0")));
        assertEquals("2.0.0", state.load().getVersion());
        assertEquals("2.0.0", metadata.getSelectedVersion());
        assertEquals("new-digest", metadata.getSelectedSha256());
    }

    @Test
    public void sameVersionPublicationPreservesExistingAsset() {
        metadata.saveSelectedRelease("2.0.0", 20L, "https://example.invalid/old.apk", "old", false, "old-digest", 42L);
        state.save(UpdateState.available("2.0.0", 1L));

        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), release("2.0.0")));
        assertEquals("old-digest", metadata.getSelectedSha256());
    }

    @Test
    public void publicationRepairsMismatchedSelectedMetadata() {
        metadata.saveSelectedRelease("3.0.0", 30L, "https://example.invalid/other.apk", "other", false, "other-digest", 42L);
        state.save(UpdateState.available("2.0.0", 1L));

        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), release("2.0.0")));
        assertEquals("2.0.0", metadata.getSelectedVersion());
        assertEquals("new-digest", metadata.getSelectedSha256());
    }

    @Test
    @Config(sdk = 33)
    public void openUpdateCenterRendersNewBackgroundRelease() {
        String digest = "0000000000000000000000000000000000000000000000000000000000000000";
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
            NotificationSettings.initializeNotificationManager(context);
            metadata.saveSelectedRelease("99.0.0", Integer.MAX_VALUE, "https://example.invalid/old.apk", "old notes", false, digest, 42L);
            state.save(UpdateState.available("99.0.0", 1L));
            VersionChecker.GitHubRelease selected = release("100.0.0");
            selected.releaseVersionCode = Integer.MAX_VALUE;
            selected.sha256 = digest;
            selected.body = "new notes";
            assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), selected));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TextView version = controller.get().findViewById(R.id.update_version);
            TextView notes = controller.get().findViewById(R.id.update_notes);
            assertTrue(version.getText().toString().contains("100.0.0"));
            assertEquals("new notes", notes.getText().toString());
            assertTrue(controller.get().findViewById(R.id.update_action).isEnabled());
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification notification = manager.getActiveNotifications()[0].getNotification();
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("100.0.0"));
        } finally {
            UpdateNotification.clear(context);
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void foregroundDownloadPreventsStalePublication() {
        long revision = state.revision();
        metadata.saveSelectedRelease("1.1.0", 11L, "https://example.invalid/old.apk", "old", false, "old-digest", 42L);
        state.save(UpdateState.downloading("1.1.0", "/data/update.part", 0L, 42L, 1L));

        assertFalse(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, revision, release("2.0.0")));
        assertEquals(UpdatePhase.DOWNLOADING, state.load().getPhase());
        assertEquals("old-digest", metadata.getSelectedSha256());
    }

    @Test
    public void supersededBackgroundCheckDoesNotRefreshSuccessTime() {
        metadata.markSuccessfulCheck(123L);
        long revision = state.revision();
        long generation = UpdateCheckCoordinator.begin();
        state.save(UpdateState.checking(1L));

        assertFalse(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, revision, generation, null));
        assertFalse(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, revision, generation, release("2.0.0")));
        assertEquals(123L, metadata.getLastSuccessfulCheck());
        assertEquals(UpdatePhase.CHECKING, state.load().getPhase());
    }

    @Test
    public void currentBackgroundCheckRecordsSuccessWithOrWithoutRelease() {
        metadata.markSuccessfulCheck(0L);
        long generation = UpdateCheckCoordinator.begin();
        assertTrue(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, state.revision(), generation, null));
        assertTrue(metadata.getLastSuccessfulCheck() > 0L);

        metadata.markSuccessfulCheck(0L);
        assertTrue(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, state.revision(), generation, release("2.0.0")));
        assertTrue(metadata.getLastSuccessfulCheck() > 0L);
        assertEquals(UpdatePhase.AVAILABLE, state.load().getPhase());
    }

    @Test
    public void newerCheckPreventsOldBackgroundSuccessWithoutStateChange() {
        metadata.markSuccessfulCheck(123L);
        long revision = state.revision();
        long older = UpdateCheckCoordinator.begin();
        long newer = UpdateCheckCoordinator.begin();

        assertFalse(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, revision, older, release("2.0.0")));
        assertEquals(123L, metadata.getLastSuccessfulCheck());
        assertEquals(UpdatePhase.IDLE, state.load().getPhase());
        assertTrue(UpdateCheckWorker.completeCheckIfUnchanged(context, state, metadata, revision, newer, null));
        assertTrue(metadata.getLastSuccessfulCheck() > 123L);
    }

    @Test
    @Config(sdk = 33)
    public void readyUpdateRestoresReleaseNotesWithoutChangingInstallAction() throws Exception {
        File apk = File.createTempFile("update-ready-", ".apk", context.getCacheDir());
        metadata.saveSelectedRelease("99.0.0", Integer.MAX_VALUE, "https://example.invalid/update.apk",
            "ready notes", false, "0000000000000000000000000000000000000000000000000000000000000000", 42L);
        state.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 42L, 1L));
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            TextView notes = controller.get().findViewById(R.id.update_notes);
            TextView action = controller.get().findViewById(R.id.update_action);
            assertEquals("ready notes", notes.getText().toString());
            assertEquals(context.getString(R.string.update_install), action.getText().toString());
            assertTrue(action.isEnabled());
        } finally {
            controller.pause().stop().destroy();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    @Config(sdk = 33)
    public void readyUpdateDoesNotDisplayNotesFromAnotherRelease() throws Exception {
        File apk = File.createTempFile("update-ready-", ".apk", context.getCacheDir());
        metadata.saveSelectedRelease("98.0.0", Integer.MAX_VALUE, "https://example.invalid/update.apk",
            "other release notes", false, "0000000000000000000000000000000000000000000000000000000000000000", 42L);
        state.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 42L, 1L));
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            TextView notes = controller.get().findViewById(R.id.update_notes);
            TextView channel = controller.get().findViewById(R.id.update_channel);
            assertEquals(context.getString(R.string.update_no_release_notes), notes.getText().toString());
            assertEquals("", channel.getText().toString());
        } finally {
            controller.pause().stop().destroy();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    @Config(sdk = 33)
    public void lateReleaseCallbackDoesNotReplaceInstallAction() throws Exception {
        File apk = File.createTempFile("update-ready-", ".apk", context.getCacheDir());
        metadata.saveSelectedRelease("99.0.0", Integer.MAX_VALUE, "https://example.invalid/update.apk",
            "ready notes", false, "0000000000000000000000000000000000000000000000000000000000000000", 42L);
        state.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 42L, 1L));
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            controller.get().onReleaseAvailable(release("99.0.0"));
            TextView action = controller.get().findViewById(R.id.update_action);
            assertEquals(context.getString(R.string.update_install), action.getText().toString());
            assertTrue(action.isEnabled());
            assertEquals(View.GONE, controller.get().findViewById(R.id.update_skip).getVisibility());
        } finally {
            controller.pause().stop().destroy();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    @Config(sdk = 33)
    public void changingToReleaseWithoutSizeClearsPreviousSize() throws Exception {
        File apk = File.createTempFile("update-ready-", ".apk", context.getCacheDir());
        String digest = "0000000000000000000000000000000000000000000000000000000000000000";
        metadata.saveSelectedRelease("99.0.0", Integer.MAX_VALUE, "https://example.invalid/old.apk",
            "old", false, digest, 1000000L);
        state.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), 1000000L, 1L));
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            TextView size = controller.get().findViewById(R.id.update_size);
            assertFalse(size.getText().toString().isEmpty());
            metadata.saveSelectedRelease("100.0.0", Integer.MAX_VALUE, "https://example.invalid/new.apk",
                "new", false, digest, -1L);
            state.save(UpdateState.available("100.0.0", 2L));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", size.getText().toString());
        } finally {
            controller.pause().stop().destroy();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    @Config(sdk = 33)
    public void checkingAndIdleDoNotShowDuplicateCheckAction() {
        ActivityController<UpdateCenterActivity> controller = Robolectric.buildActivity(UpdateCenterActivity.class).setup();
        try {
            View secondary = controller.get().findViewById(R.id.update_secondary);
            View progressCard = controller.get().findViewById(R.id.update_progress_card);
            MaterialButton action = controller.get().findViewById(R.id.update_action);
            controller.get().onUpdateState(UpdateState.checking(1L));
            assertEquals(View.GONE, secondary.getVisibility());
            assertEquals(View.GONE, progressCard.getVisibility());
            assertFalse(action.isEnabled());
            assertTrue(action.getIcon() instanceof AnimatedVectorDrawable);
            controller.get().onUpdateState(UpdateState.idle(2L));
            assertEquals(View.GONE, secondary.getVisibility());
            assertTrue(action.isEnabled());
            assertNull(action.getIcon());
            controller.get().onUpdateState(UpdateState.downloading("2.0.0", "/tmp/update.apk", 0L, 100L, 3L));
            assertEquals(View.VISIBLE, progressCard.getVisibility());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static VersionChecker.GitHubRelease release(String version) {
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = version;
        release.downloadUrl = "https://example.invalid/new.apk";
        release.sha256 = "new-digest";
        release.releaseVersionCode = 20L;
        release.sizeBytes = 100L;
        return release;
    }
}
