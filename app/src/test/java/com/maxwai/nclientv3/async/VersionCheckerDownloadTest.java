// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

@RunWith(RobolectricTestRunner.class)
public class VersionCheckerDownloadTest {
    private ActivityController<AppCompatActivity> controller;
    private VersionChecker checker;
    private UpdateStateStore store;
    private File previousUpdateFolder;

    @Before
    public void setUp() throws Exception {
        controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        AppCompatActivity activity = controller.get();
        store = new UpdateStateStore(activity);
        store.clear();
        store.save(UpdateState.available("99.0.0", 1L));
        previousUpdateFolder = Global.UPDATEFOLDER;
        Global.UPDATEFOLDER = new File(activity.getCacheDir(), "update-download-test");
        assertTrue(Global.UPDATEFOLDER.isDirectory() || Global.UPDATEFOLDER.mkdirs());
        checker = new VersionChecker(activity, false, null, false);
        VersionChecker.GitHubRelease release = new VersionChecker.GitHubRelease();
        release.versionCode = "99.0.0";
        setField("latestRelease", release);
    }

    @After
    public void tearDown() {
        checker.cancel();
        store.clear();
        Global.UPDATEFOLDER = previousUpdateFolder;
        controller.pause().stop().destroy();
    }

    @Test
    public void connectionFailurePersistsAnError() throws Exception {
        int unusedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            unusedPort = server.getLocalPort();
        }
        setField("downloadUrl", "http://127.0.0.1:" + unusedPort + "/update.apk");

        checker.startDownload();
        awaitPhase(UpdatePhase.ERROR);

        assertEquals("download", store.load().getErrorCode());
    }

    @Test
    public void cancelAndRetryKeepTheNewAttemptWhenOldCallFinishes() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch firstAccepted = new CountDownLatch(1);
            CountDownLatch secondAccepted = new CountDownLatch(1);
            CountDownLatch releaseServer = new CountDownLatch(1);
            Thread serverThread = new Thread(() -> {
                try (Socket first = server.accept()) {
                    first.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
                    first.getOutputStream().flush();
                    firstAccepted.countDown();
                    try (Socket second = server.accept()) {
                        secondAccepted.countDown();
                        releaseServer.await(10, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                }
            });
            serverThread.start();
            try {
                setField("downloadUrl", "http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
                checker.startDownload();
                assertTrue(firstAccepted.await(5, TimeUnit.SECONDS));
                String firstPath = store.load().getFilePath();
                long fileDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((!new File(firstPath).isFile() || new File(firstPath).length() == 0L)
                    && System.nanoTime() < fileDeadline) Thread.sleep(10L);
                assertTrue(new File(firstPath).length() > 0L);
                VersionChecker other = new VersionChecker(controller.get(), false, null, false);
                VersionChecker.GitHubRelease sameRelease = new VersionChecker.GitHubRelease();
                sameRelease.versionCode = "99.0.0";
                setField(other, "latestRelease", sameRelease);
                setField(other, "downloadUrl", "http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
                other.startDownload();
                assertEquals(firstPath, store.load().getFilePath());
                assertEquals(0, ((OkHttpClient) getField(other, "updateClient")).dispatcher().runningCallsCount());
                File folder = Global.UPDATEFOLDER;
                File invalidFolder = File.createTempFile("nreader-update", ".tmp", controller.get().getCacheDir());
                Global.UPDATEFOLDER = invalidFolder;
                try {
                    other.startDownload();
                    assertEquals(firstPath, store.load().getFilePath());
                } finally {
                    Global.UPDATEFOLDER = folder;
                    //noinspection ResultOfMethodCallIgnored
                    invalidFolder.delete();
                }
                checker.cancelDownload();
                assertEquals(UpdatePhase.AVAILABLE, store.load().getPhase());

                checker.startDownload();
                assertTrue(secondAccepted.await(5, TimeUnit.SECONDS));
                String secondPath = store.load().getFilePath();
                assertNotEquals(firstPath, secondPath);
                OkHttpClient client = (OkHttpClient) getField("updateClient");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (client.dispatcher().runningCallsCount() != 1 && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertEquals(1, client.dispatcher().runningCallsCount());
                assertEquals(UpdatePhase.DOWNLOADING, store.load().getPhase());
                assertEquals(secondPath, store.load().getFilePath());
                assertTrue(!new File(firstPath).exists());
            } finally {
                checker.cancel();
                releaseServer.countDown();
                serverThread.join(5000L);
            }
        }
    }

    @Test
    public void installValidationIsDeduplicatedAndCannotPublishAfterStateChanges() throws Exception {
        File apk = File.createTempFile("nreader-install", ".apk", controller.get().getCacheDir());
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(new byte[] {1, 2, 3});
        }
        new UpdateMetadataStore(controller.get()).saveSelectedRelease(
            "99.0.0", 99L, "https://example.invalid/update.apk", "", false,
            UpdateFileVerifier.sha256(apk), apk.length());
        store.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), apk.length(), 1L));
        ExecutorService executor = (ExecutorService) getField("INSTALL_VALIDATOR");
        AtomicBoolean active = (AtomicBoolean) getField("INSTALL_VALIDATION_ACTIVE");
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                unblock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(checker.installReadyUpdate());
            assertFalse(checker.installReadyUpdate());
            store.save(UpdateState.idle(2L));
            unblock.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (active.get() && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10L);
            }
            assertFalse(active.get());
            assertEquals(UpdatePhase.IDLE, store.load().getPhase());
        } finally {
            unblock.countDown();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    public void installValidationDoesNotPublishAfterActivityPauses() throws Exception {
        File apk = File.createTempFile("nreader-install", ".apk", controller.get().getCacheDir());
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(new byte[] {1, 2, 3});
        }
        new UpdateMetadataStore(controller.get()).saveSelectedRelease(
            "99.0.0", 99L, "https://example.invalid/update.apk", "", false,
            UpdateFileVerifier.sha256(apk), apk.length());
        store.save(UpdateState.readyToInstall("99.0.0", apk.getAbsolutePath(), apk.length(), 1L));
        ExecutorService executor = (ExecutorService) getField("INSTALL_VALIDATOR");
        AtomicBoolean active = (AtomicBoolean) getField("INSTALL_VALIDATION_ACTIVE");
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                unblock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(checker.installReadyUpdate());
            controller.pause();
            unblock.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (active.get() && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10L);
            }
            assertFalse(active.get());
            assertEquals(UpdatePhase.READY_TO_INSTALL, store.load().getPhase());
        } finally {
            unblock.countDown();
            controller.resume();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    private void awaitPhase(UpdatePhase phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.load().getPhase() != phase && System.nanoTime() < deadline) Thread.sleep(10L);
        assertEquals(phase, store.load().getPhase());
    }

    private void setField(String name, Object value) throws Exception {
        setField(checker, name, value);
    }

    private void setField(VersionChecker target, String name, Object value) throws Exception {
        Field field = VersionChecker.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(String name) throws Exception {
        return getField(checker, name);
    }

    private Object getField(VersionChecker target, String name) throws Exception {
        Field field = VersionChecker.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
