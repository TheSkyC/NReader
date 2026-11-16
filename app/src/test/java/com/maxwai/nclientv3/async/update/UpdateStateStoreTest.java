// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
