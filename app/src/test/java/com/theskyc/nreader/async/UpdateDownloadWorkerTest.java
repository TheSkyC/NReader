// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.async.update.UpdateDownloadRecord;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;
import com.theskyc.nreader.settings.Global;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
public class UpdateDownloadWorkerTest {
    private static final String DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private Context context;
    private UpdateStateStore store;
    private File previousFolder;
    private File folder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new UpdateStateStore(context);
        store.clear();
        previousFolder = Global.UPDATEFOLDER;
        folder = new File(context.getCacheDir(), "update-worker-" + UUID.randomUUID());
        assertTrue(folder.mkdir());
        Global.UPDATEFOLDER = folder;
    }

    @After
    public void tearDown() {
        store.clear();
        Global.UPDATEFOLDER = previousFolder;
        //noinspection ResultOfMethodCallIgnored
        folder.delete();
    }

    @Test
    public void downloadsAndVerifiesScheduledArtifact() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread origin = serve(server, null, null);
            UpdateDownloadRecord record = record("http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
            assertTrue(store.claimScheduledDownload(record, 1L));
            try {
                assertEquals(ListenableWorker.Result.success(), worker(record.getId()).doWork());
                assertEquals(UpdatePhase.READY_TO_INSTALL, store.load().getPhase());
                File apk = new File(store.load().getFilePath());
                assertTrue(apk.isFile());
                assertEquals(DIGEST, UpdateFileVerifier.sha256(apk));
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            } finally {
                origin.join(5000L);
            }
        }
    }

    @Test
    public void stoppedWorkerReturnsTaskToQueue() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread origin = serve(server, started, release);
            UpdateDownloadRecord record = record("http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
            assertTrue(store.claimScheduledDownload(record, 1L));
            UpdateDownloadWorker worker = worker(record.getId());
            Thread execution = new Thread(worker::doWork);
            execution.start();
            try {
                assertTrue(started.await(5L, TimeUnit.SECONDS));
                worker.stop(WorkInfo.STOP_REASON_PREEMPT);
                release.countDown();
                execution.join(5000L);
                assertFalse(execution.isAlive());
                assertEquals(UpdatePhase.QUEUED, store.load().getPhase());
                assertEquals(record.getId(), store.loadDownloadRecord().getId());
                assertFalse(new File(record.getFilePath()).exists());
            } finally {
                release.countDown();
                execution.join(5000L);
                origin.join(5000L);
            }
        }
    }

    @Test
    public void verifiedFinalArtifactSurvivesInterruptedPublication() throws Exception {
        UpdateDownloadRecord record = record("https://example.invalid/update.apk");
        File apk = new File(record.getFilePath().substring(0, record.getFilePath().length() - 5));
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write("abc".getBytes(StandardCharsets.US_ASCII));
        }
        try {
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.success(), worker(record.getId()).doWork());
            assertEquals(UpdatePhase.READY_TO_INSTALL, store.load().getPhase());
            assertEquals(apk.getAbsolutePath(), store.load().getFilePath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    @Test
    public void interruptedPartialIsDownloadedAgainAndVerified() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread origin = serve(server, null, null);
            UpdateDownloadRecord record = record("http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
            try (FileOutputStream output = new FileOutputStream(record.getFilePath())) {
                output.write("stale partial".getBytes(StandardCharsets.US_ASCII));
            }
            try {
                assertTrue(store.claimScheduledDownload(record, 1L));
                store.save(UpdateState.downloading(record.getVersion(), record.getFilePath(), 2L, 3L, 2L));
                assertEquals(ListenableWorker.Result.success(), worker(record.getId()).doWork());
                File apk = new File(store.load().getFilePath());
                assertEquals(3L, apk.length());
                assertEquals(DIGEST, UpdateFileVerifier.sha256(apk));
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            } finally {
                origin.join(5000L);
                //noinspection ResultOfMethodCallIgnored
                new File(record.getFilePath()).delete();
            }
        }
    }

    @Test
    public void alreadyInstalledVersionClearsScheduledTaskWithoutDownload() throws Exception {
        UUID id = UUID.randomUUID();
        String flavor = BuildConfig.FLAVOR.equals("minApi26") ? "minApi26" : "minApi28";
        File partial = new File(folder, "NReader-0.0.0-" + flavor + "-" + id + ".apk.part");
        assertTrue(partial.createNewFile());
        UpdateDownloadRecord record = new UpdateDownloadRecord(id, "0.0.0", partial.getAbsolutePath(),
            "https://example.invalid/update.apk", DIGEST, 3L);
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.saveSelectedRelease("0.0.0", BuildConfig.VERSION_CODE, record.getUrl(), null, false, DIGEST, 3L);
        try {
            store.save(UpdateState.available("0.0.0", 1L));
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.success(), worker(id).doWork());
            assertEquals(UpdatePhase.IDLE, store.load().getPhase());
            assertFalse(partial.exists());
        } finally {
            metadata.clearSelectedRelease();
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    @Test
    public void invalidPathDoesNotDeleteOutsideFile() throws Exception {
        File outside = File.createTempFile("update-outside-", ".part", context.getCacheDir());
        UpdateDownloadRecord record = new UpdateDownloadRecord(UUID.randomUUID(), "99.0.0", outside.getAbsolutePath(),
            "https://example.invalid/update.apk", DIGEST, 3L);
        try {
            store.save(UpdateState.available("99.0.0", 1L));
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.failure(), worker(record.getId()).doWork());
            assertTrue(outside.isFile());
            assertEquals(UpdatePhase.ERROR, store.load().getPhase());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            outside.delete();
        }
    }

    @Test
    public void disabledSourceDoesNotStartNetworkRequest() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(500);
            UpdateDownloadRecord record = record("http://127.0.0.1:" + server.getLocalPort() + "/update.apk");
            assertTrue(store.claimScheduledDownload(record, 1L));
            assertEquals(ListenableWorker.Result.failure(), worker(record.getId(), () -> false).doWork());
            assertEquals(UpdatePhase.ERROR, store.load().getPhase());
            try (Socket ignored = server.accept()) {
                throw new AssertionError("Disabled update source was contacted");
            } catch (SocketTimeoutException expected) {
                // The disabled source must not issue a request.
            }
        }
    }

    private UpdateDownloadRecord record(String url) {
        UUID id = UUID.randomUUID();
        String flavor = BuildConfig.FLAVOR.equals("minApi26") ? "minApi26" : "minApi28";
        File partial = new File(folder, "NReader-99.0.0-" + flavor + "-" + id + ".apk.part");
        store.save(UpdateState.available("99.0.0", 1L));
        return new UpdateDownloadRecord(id, "99.0.0", partial.getAbsolutePath(), url, DIGEST, 3L);
    }

    private UpdateDownloadWorker worker(UUID id) {
        return worker(id, () -> true);
    }

    private UpdateDownloadWorker worker(UUID id, BooleanSupplier sourceEnabled) {
        Executor executor = Runnable::run;
        WorkerFactory factory = new WorkerFactory() {
            @Override public ListenableWorker createWorker(Context context, String workerClassName, WorkerParameters parameters) {
                return null;
            }
        };
        WorkerParameters parameters = new WorkerParameters(id, Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, executor, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(executor), factory,
            (context, uuid, data) -> Futures.immediateFuture(null),
            (context, uuid, info) -> Futures.immediateFuture(null));
        return new UpdateDownloadWorker(context, parameters, sourceEnabled);
    }

    private static Thread serve(ServerSocket server, CountDownLatch started, CountDownLatch release) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\na".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                if (started != null) started.countDown();
                if (release != null && !release.await(5L, TimeUnit.SECONDS)) return;
                socket.getOutputStream().write("bc".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        return thread;
    }
}
