// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
    public void availablePublicationPreservesExistingAsset() {
        metadata.saveSelectedRelease("1.1.0", 11L, "https://example.invalid/old.apk", "old", false, "old-digest", 42L);
        state.save(UpdateState.available("1.1.0", 1L));

        assertTrue(UpdateCheckWorker.publishIfUnchanged(context, state, metadata, state.revision(), release("2.0.0")));
        assertEquals("1.1.0", state.load().getVersion());
        assertEquals("1.1.0", metadata.getSelectedVersion());
        assertEquals("old-digest", metadata.getSelectedSha256());
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
