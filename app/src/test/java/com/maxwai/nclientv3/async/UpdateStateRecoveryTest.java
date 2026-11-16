// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class UpdateStateRecoveryTest {
    @Test
    public void completedInstallIsClearedWhenRunningVersionIsNewer() {
        Context context = RuntimeEnvironment.getApplication();
        UpdateState state = UpdateState.installing("1.0.0-beta.1", "/missing.apk", 1L);

        UpdateState recovered = UpdateStateRecovery.reconcile(context, state);

        assertEquals(UpdatePhase.IDLE, recovered.getPhase());
    }

    @Test
    public void completedInstallRemovesDownloadedUpdateArtifact() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "NReader-1.0.0-beta.1-minApi28-release.apk");
        if (!file.createNewFile()) throw new IOException("Unable to create test artifact");
        try {
            UpdateState state = UpdateState.installing("1.0.0-beta.1", file.getAbsolutePath(), 1L);

            UpdateStateRecovery.reconcile(context, state);

            assertFalse(file.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void installerCancellationReturnsToReadyWhenFileRemains() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("nreader-update", ".apk", context.getCacheDir());
        try {
            UpdateState state = UpdateState.installing("99.0.0", file.getAbsolutePath(), 1L);

            UpdateState recovered = UpdateStateRecovery.reconcile(context, state);

            assertEquals(UpdatePhase.READY_TO_INSTALL, recovered.getPhase());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void interruptedDownloadRemovesTemporaryPartFile() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("nreader-update", ".apk.part", context.getCacheDir());
        try {
            UpdateState state = UpdateState.downloading("99.0.0", file.getAbsolutePath(), 10L, 100L, 1L);

            UpdateState recovered = UpdateStateRecovery.reconcile(context, state);

            assertEquals(UpdatePhase.ERROR, recovered.getPhase());
            assertEquals(false, file.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void storedDownloadWithoutAnActiveOwnerIsRecoveredAsInterrupted() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        UpdateStateStore store = new UpdateStateStore(context);
        File file = File.createTempFile("nreader-update", ".apk.part", context.getCacheDir());
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
}
