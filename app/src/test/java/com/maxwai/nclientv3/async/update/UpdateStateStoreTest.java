// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
public class UpdateStateStoreTest {
    private Context context;
    private UpdateStateStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new UpdateStateStore(context);
        store.clear();
    }

    @After
    public void tearDown() {
        store.clear();
    }

    @Test
    public void savesAndLoadsReadyState() {
        UpdateState expected = UpdateState.readyToInstall("1.1.0", "/data/update.apk", 42L, 123L);

        store.save(expected);
        UpdateState actual = store.load();

        assertEquals(UpdatePhase.READY_TO_INSTALL, actual.getPhase());
        assertEquals("1.1.0", actual.getVersion());
        assertEquals("/data/update.apk", actual.getFilePath());
        assertEquals(42L, actual.getTotalBytes());
        assertTrue(actual.isReadyToInstall());
    }

    @Test
    public void malformedStoredPhaseFallsBackToIdle() {
        context.getSharedPreferences("UpdateState", Context.MODE_PRIVATE)
            .edit()
            .putString("phase", "NOT_A_PHASE")
            .commit();

        assertEquals(UpdatePhase.IDLE, store.load().getPhase());
    }

    @Test
    public void backgroundCommitIsRejectedAfterForegroundTransitionAndBackToIdle() {
        long revision = store.revision();
        UpdateStateStore other = new UpdateStateStore(context);
        other.save(UpdateState.checking(1L));
        other.save(UpdateState.idle(2L));

        assertFalse(store.runIfCheckableAndUnchanged(revision,
            () -> store.save(UpdateState.available("2.0.0", 3L))));
        assertEquals(UpdatePhase.IDLE, store.load().getPhase());
    }

    @Test
    public void backgroundCommitDoesNotReplaceReadyToInstall() {
        store.save(UpdateState.readyToInstall("1.1.0", "/data/update.apk", 42L, 1L));
        long revision = store.revision();

        assertFalse(store.runIfCheckableAndUnchanged(revision,
            () -> store.save(UpdateState.available("2.0.0", 2L))));
        assertEquals(UpdatePhase.READY_TO_INSTALL, store.load().getPhase());
    }

    @Test
    public void backgroundCommitCanPublishWhenIdleIsUnchanged() {
        long revision = store.revision();

        assertTrue(store.runIfCheckableAndUnchanged(revision,
            () -> store.save(UpdateState.available("2.0.0", 2L))));
        assertEquals("2.0.0", store.load().getVersion());
    }

    @Test
    public void backgroundCheckCanNotifyWhileExistingReleaseIsAvailable() {
        store.save(UpdateState.available("1.1.0", 1L));
        long revision = store.revision();
        AtomicBoolean notified = new AtomicBoolean();

        assertTrue(store.runIfCheckableAndUnchanged(revision, () -> notified.set(true)));
        assertTrue(notified.get());
        assertEquals("1.1.0", store.load().getVersion());
    }

    @Test
    public void foregroundChangeWhileBackgroundRequestIsInFlightPreventsPublish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishRequest = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        Thread background = new Thread(() -> {
            long revision = store.revision();
            started.countDown();
            try {
                if (!finishRequest.await(5, TimeUnit.SECONDS)) return;
                published.set(store.runIfCheckableAndUnchanged(revision,
                    () -> store.save(UpdateState.available("2.0.0", 2L))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        background.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        new UpdateStateStore(context).save(UpdateState.downloading("1.1.0", "/data/update.part", 0L, 42L, 1L));
        finishRequest.countDown();
        background.join(5000L);

        assertFalse(background.isAlive());
        assertFalse(published.get());
        assertEquals(UpdatePhase.DOWNLOADING, store.load().getPhase());
    }

    @Test
    public void canceledAttemptCannotPublishOverRetry() throws Exception {
        String oldPath = "/data/old.apk.part";
        String newPath = "/data/new.apk.part";
        store.save(UpdateState.downloading("1.1.0", oldPath, 0L, 42L, 1L));
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        Thread oldDownload = new Thread(() -> {
            paused.countDown();
            try {
                if (resume.await(5, TimeUnit.SECONDS)) {
                    published.set(store.runIfDownloading(oldPath,
                        () -> store.save(UpdateState.error("1.1.0", oldPath, "download", 4L))));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        oldDownload.start();
        assertTrue(paused.await(5, TimeUnit.SECONDS));
        assertTrue(store.runIfDownloading(oldPath, () -> store.save(UpdateState.available("1.1.0", 2L))));
        store.save(UpdateState.downloading("1.1.0", newPath, 0L, 42L, 3L));
        resume.countDown();
        oldDownload.join(5000L);

        assertFalse(oldDownload.isAlive());
        assertFalse(published.get());
        assertEquals(UpdatePhase.DOWNLOADING, store.load().getPhase());
        assertEquals(newPath, store.load().getFilePath());
    }

    @Test
    public void staleForegroundCheckCannotReplaceDownload() {
        store.save(UpdateState.checking(1L));
        long revision = store.revision();
        store.save(UpdateState.available("1.1.0", 2L));
        assertTrue(store.runIfDownloadable("1.1.0", () ->
            store.save(UpdateState.downloading("1.1.0", "/data/current.part", 0L, 42L, 3L))));

        assertFalse(store.runIfUnchanged(revision, () -> store.save(UpdateState.idle(4L))));
        assertFalse(store.runIfDownloadable("1.1.0", () -> store.save(UpdateState.idle(5L))));
        assertEquals("/data/current.part", store.load().getFilePath());
    }

    @Test
    public void claimPersistsSnapshotAndProgressPreservesIt() {
        store.save(UpdateState.available("1.1.0", 1L));
        UUID id = UUID.randomUUID();
        UpdateDownloadRecord record = new UpdateDownloadRecord(id, "1.1.0", "/data/update.part",
            "https://example.invalid/update.apk", "digest", 42L);

        assertTrue(store.claimDownload(record, 2L));
        UpdateDownloadRecord restored = new UpdateStateStore(context).loadDownloadRecord();
        assertNotNull(restored);
        assertEquals(id, restored.getId());
        assertEquals(record.getVersion(), restored.getVersion());
        assertEquals(record.getFilePath(), restored.getFilePath());
        assertEquals(record.getUrl(), restored.getUrl());
        assertEquals(record.getSha256(), restored.getSha256());
        assertEquals(42L, restored.getSize());
        store.save(UpdateState.downloading("1.1.0", "/data/update.part", 21L, 42L, 3L));
        assertEquals(id, store.loadDownloadRecord().getId());
        store.save(UpdateState.available("1.1.0", 4L));
        assertNull(store.loadDownloadRecord());
        assertFalse(context.getSharedPreferences("UpdateState", Context.MODE_PRIVATE).contains("task_url"));
    }

    @Test
    public void competingClaimCannotReplaceActiveSnapshot() {
        store.save(UpdateState.available("1.1.0", 1L));
        UpdateDownloadRecord first = new UpdateDownloadRecord(UUID.randomUUID(), "1.1.0", "/data/first.part",
            "https://example.invalid/first.apk", "first-digest", 42L);
        UpdateDownloadRecord second = new UpdateDownloadRecord(UUID.randomUUID(), "1.1.0", "/data/second.part",
            "https://example.invalid/second.apk", "second-digest", 42L);
        assertTrue(store.claimDownload(first, 2L));
        assertFalse(store.claimDownload(second, 3L));
        assertEquals(first.getId(), store.loadDownloadRecord().getId());
        assertEquals(first.getFilePath(), store.load().getFilePath());
        store.save(UpdateState.downloading("1.1.0", "/data/other.part", 0L, 42L, 4L));
        assertNull(store.loadDownloadRecord());
    }

    @Test
    public void oldOrMalformedDownloadHasNoUsableSnapshot() {
        store.save(UpdateState.downloading("1.1.0", "/data/update.part", 1L, 42L, 1L));
        assertNull(store.loadDownloadRecord());
        context.getSharedPreferences("UpdateState", Context.MODE_PRIVATE).edit()
            .putString("task_id", "invalid").putString("task_url", "https://example.invalid/update.apk")
            .putString("task_sha256", "digest").putLong("task_size", 42L).commit();
        assertNull(store.loadDownloadRecord());
    }

    @Test
    public void scheduledAttemptRetainsIdentityAcrossQueueAndRunningTransitions() {
        store.save(UpdateState.available("1.1.0", 1L));
        UpdateDownloadRecord record = new UpdateDownloadRecord(UUID.randomUUID(), "1.1.0", "/data/update.part",
            "https://example.invalid/update.apk", "digest", 42L);
        assertTrue(store.claimScheduledDownload(record, 2L));
        assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        assertTrue(store.isScheduledDownload());
        assertFalse(store.runIfScheduledDownload(UUID.randomUUID(), () -> fail("Wrong task must not run")));
        assertTrue(store.runIfScheduledDownload(record.getId(), () ->
            store.save(UpdateState.downloading(record.getVersion(), record.getFilePath(), 0L, 42L, 3L))));
        assertEquals(record.getId(), store.loadDownloadRecord().getId());
        assertTrue(store.runIfScheduledDownload(record.getId(), () ->
            store.save(UpdateState.queued(record.getVersion(), record.getFilePath(), 42L, 4L))));
        assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
        assertEquals(record.getId(), store.loadDownloadRecord().getId());
        store.save(UpdateState.available(record.getVersion(), 5L));
        assertFalse(store.isScheduledDownload());
        assertFalse(store.runIfScheduledDownload(record.getId(), () -> fail("Canceled task must not run")));
    }
}
