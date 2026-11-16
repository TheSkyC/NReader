// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class VersionCheckerQuietCheckTest {
    private ActivityController<AppCompatActivity> controller;
    private UpdateStateStore store;
    private VersionChecker checker;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        store = new UpdateStateStore(controller.get());
        store.clear();
        new UpdateMetadataStore(controller.get()).clearSelectedRelease();
        checker = new VersionChecker(controller.get(), true, null, false);
    }

    @After
    public void tearDown() {
        checker.cancel();
        store.clear();
        new UpdateMetadataStore(controller.get()).clearSelectedRelease();
        controller.pause().stop().destroy();
    }

    @Test
    public void silentCheckPublishesReleaseWithoutInterruptingOrDownloading() throws Exception {
        publishRelease("Release notes");
    }

    @Test
    public void silentCheckStillPublishesReleaseWithoutNotes() throws Exception {
        publishRelease(null);
    }

    private void publishRelease(String notes) throws Exception {
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = "99.0.0";
        release.releaseVersionCode = Integer.MAX_VALUE;
        release.downloadUrl = "https://example.invalid/update.apk";
        release.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        release.body = notes;
        store.save(UpdateState.checking(1L));
        Field revision = VersionChecker.class.getDeclaredField("checkRevision");
        revision.setAccessible(true);
        revision.setLong(checker, store.revision());
        Method publish = VersionChecker.class.getDeclaredMethod("publishRelease", VersionChecker.GitHubRelease.class, boolean.class);
        publish.setAccessible(true);
        publish.invoke(checker, release, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(UpdatePhase.AVAILABLE, store.load().getPhase());
        assertEquals("99.0.0", store.load().getVersion());
        assertEquals(notes, new UpdateMetadataStore(controller.get()).getSelectedBody());
        assertFalse(store.isScheduledDownload());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
    }
}
