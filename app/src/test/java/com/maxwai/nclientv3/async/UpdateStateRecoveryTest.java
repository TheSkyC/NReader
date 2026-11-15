// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;

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
}
