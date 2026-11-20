// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;

import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.annotation.Config;

@Config(sdk = 26)
public class UpdateCenterAndroid8Test extends UpdateCheckWorkerPublicationTest {
    @Before
    public void verifyAndroid8Runtime() {
        assertEquals(26, Build.VERSION.SDK_INT);
    }

    @Override
    @Test
    public void openUpdateCenterRendersNewBackgroundRelease() {
        super.openUpdateCenterRendersNewBackgroundRelease();
    }

    @Override
    @Test
    public void readyUpdateRestoresReleaseNotesWithoutChangingInstallAction() throws Exception {
        super.readyUpdateRestoresReleaseNotesWithoutChangingInstallAction();
    }

    @Override
    @Test
    public void readyUpdateDoesNotDisplayNotesFromAnotherRelease() throws Exception {
        super.readyUpdateDoesNotDisplayNotesFromAnotherRelease();
    }

    @Override
    @Test
    public void lateReleaseCallbackDoesNotReplaceInstallAction() throws Exception {
        super.lateReleaseCallbackDoesNotReplaceInstallAction();
    }

    @Override
    @Test
    public void changingToReleaseWithoutSizeClearsPreviousSize() throws Exception {
        super.changingToReleaseWithoutSizeClearsPreviousSize();
    }

    @Override
    @Test
    public void checkingAndIdleDoNotShowDuplicateCheckAction() {
        super.checkingAndIdleDoNotShowDuplicateCheckAction();
    }
}
