// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateStateTest {
    @Test
    public void normalizesProgressAndTracksReadyState() {
        UpdateState state = UpdateState.downloading("1.1.0", "/tmp/update.apk", -4, 100, 10);

        assertEquals(0L, state.getDownloadedBytes());
        assertEquals(100L, state.getTotalBytes());
        assertTrue(state.hasKnownProgress());
        assertFalse(state.isReadyToInstall());
    }

    @Test
    public void readyStateRequiresVersionAndFile() {
        UpdateState state = UpdateState.readyToInstall("1.1.0", "/tmp/update.apk", 100, 10);

        assertEquals(UpdatePhase.READY_TO_INSTALL, state.getPhase());
        assertTrue(state.isReadyToInstall());
    }

    @Test
    public void unknownProgressDoesNotClaimPercentage() {
        UpdateState state = UpdateState.downloading("1.1.0", "/tmp/update.apk", 10, 0, 10);

        assertFalse(state.hasKnownProgress());
    }
}
