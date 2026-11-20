// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.BuildConfig;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class UpdateCheckConcurrencyTest {
    @Test
    public void laterForegroundCheckWinsOverDelayedBackgroundResponse() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("[]", "initial");
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        ActivityController<AppCompatActivity> activity = Robolectric.buildActivity(AppCompatActivity.class).setup();
        VersionChecker checker = new VersionChecker(activity.get(), true, null, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch oldArrived = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        try (ServerSocket server = server()) {
            Future<?> replies = executor.submit(() -> { respondOutOfOrder(server, oldArrived, releaseOld, "[ ]"); return null; });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/releases";
            UpdateCheckWorker worker = worker(context);
            Future<String> old = executor.submit(() -> worker.fetchReleaseJson(metadata, false, url));
            assertTrue(oldArrived.await(5, TimeUnit.SECONDS));
            state.save(UpdateState.checking(1L));
            Field revision = VersionChecker.class.getDeclaredField("checkRevision");
            revision.setAccessible(true);
            revision.setLong(checker, state.revision());
            checker.requestReleases(false, true, true, url);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (state.load().getPhase() == UpdatePhase.CHECKING && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10L);
            }
            assertEquals(UpdatePhase.IDLE, state.load().getPhase());
            assertEquals("[ ]", metadata.getBody());
            assertEquals("new-tag", metadata.getEtag());
            long successful = metadata.getLastSuccessfulCheck();
            assertTrue(successful > 0L);
            releaseOld.countDown();
            expectFailure(old);
            replies.get(5, TimeUnit.SECONDS);
            assertEquals("[ ]", metadata.getBody());
            assertEquals("new-tag", metadata.getEtag());
            assertEquals(successful, metadata.getLastSuccessfulCheck());
        } finally {
            releaseOld.countDown();
            checker.cancel();
            executor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
            activity.pause().stop().destroy();
        }
    }

    @Test
    public void failedNewerCheckDoesNotPermitOlderResponseToOverwriteCache() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("[]", "initial");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch oldArrived = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        try (ServerSocket server = server()) {
            Future<?> replies = executor.submit(() -> { respondOutOfOrder(server, oldArrived, releaseOld, "{"); return null; });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/releases";
            UpdateCheckWorker worker = worker(context);
            Future<String> old = executor.submit(() -> worker.fetchReleaseJson(metadata, false, url));
            assertTrue(oldArrived.await(5, TimeUnit.SECONDS));
            Future<String> newer = executor.submit(() -> worker.fetchReleaseJson(metadata, false, url));
            expectFailure(newer);
            releaseOld.countDown();
            expectFailure(old);
            replies.get(5, TimeUnit.SECONDS);
            assertEquals("[]", metadata.getBody());
            assertEquals("initial", metadata.getEtag());
        } finally {
            releaseOld.countDown();
            executor.shutdownNow();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void unconditionalRetryKeepsItsOriginalGeneration() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("{", "invalid-tag");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch retryArrived = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        try (ServerSocket server = server()) {
            Future<?> replies = executor.submit(() -> {
                try (Socket initial = server.accept()) {
                    readRequest(initial);
                    initial.getOutputStream().write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                    initial.getOutputStream().flush();
                }
                try (Socket retry = server.accept()) {
                    readRequest(retry);
                    retryArrived.countDown();
                    try (Socket newer = server.accept()) {
                        readRequest(newer);
                        writeResponse(newer, "[ ]", "new-tag");
                    }
                    if (!releaseRetry.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to release retry");
                    writeResponse(retry, "[]", "old-tag");
                }
                return null;
            });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/releases";
            UpdateCheckWorker worker = worker(context);
            Future<String> old = executor.submit(() -> worker.fetchReleaseJson(metadata, false, url));
            assertTrue(retryArrived.await(5, TimeUnit.SECONDS));
            Future<String> newer = executor.submit(() -> worker.fetchReleaseJson(metadata, false, url));
            assertEquals("[ ]", newer.get(10, TimeUnit.SECONDS));
            releaseRetry.countDown();
            expectFailure(old);
            replies.get(5, TimeUnit.SECONDS);
            assertEquals("[ ]", metadata.getBody());
            assertEquals("new-tag", metadata.getEtag());
        } finally {
            releaseRetry.countDown();
            executor.shutdownNow();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void stoppedWorkerCancelsRequestBeforeResponseHeaders() throws Exception {
        stoppedWorkerCancelsRequest(false);
    }

    @Test
    public void stoppedWorkerCancelsRequestWhileReadingBody() throws Exception {
        stoppedWorkerCancelsRequest(true);
    }

    private void stoppedWorkerCancelsRequest(boolean sendPartialBody) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("[]", "initial");
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = server()) {
            Future<?> serverTask = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    readRequest(socket);
                    if (sendPartialBody) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nETag: new-tag\r\n"
                            + "Content-Length: 2\r\nConnection: close\r\n\r\n[").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    requestStarted.countDown();
                    if (!releaseServer.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to close server");
                }
                return null;
            });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/releases";
            UpdateCheckWorker worker = worker(context, url);
            Future<ListenableWorker.Result> execution = executor.submit(worker::doWork);
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            worker.stop(WorkInfo.STOP_REASON_PREEMPT);
            assertEquals(ListenableWorker.Result.retry(), execution.get(5, TimeUnit.SECONDS));
            assertEquals("[]", metadata.getBody());
            assertEquals("initial", metadata.getEtag());
            assertEquals(0L, metadata.getLastSuccessfulCheck());
            assertEquals(UpdatePhase.IDLE, state.load().getPhase());
            releaseServer.countDown();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void stoppedWorkerCancelsDelayedManifestWithoutPublishing() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch manifestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = server()) {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            String release = "[{\"tag_name\":\"99.0.0\",\"assets\":["
                + "{\"name\":\"NReader-99.0.0-" + BuildConfig.FLAVOR + "-release.apk\","
                + "\"browser_download_url\":\"https://example.invalid/update.apk\","
                + "\"digest\":\"0000000000000000000000000000000000000000000000000000000000000000\"},"
                + "{\"name\":\"nreader-update.json\",\"browser_download_url\":\"" + base + "/manifest\"}]}]";
            Future<?> serverTask = executor.submit(() -> {
                try (Socket list = server.accept()) {
                    readRequest(list);
                    writeResponse(list, release, "list-tag");
                }
                try (Socket manifest = server.accept()) {
                    readRequest(manifest);
                    manifestStarted.countDown();
                    if (!releaseServer.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to close server");
                }
                return null;
            });
            UpdateCheckWorker worker = worker(context, base + "/releases");
            Future<ListenableWorker.Result> execution = executor.submit(worker::doWork);
            assertTrue(manifestStarted.await(5, TimeUnit.SECONDS));
            assertEquals("list-tag", metadata.getEtag());
            worker.stop(WorkInfo.STOP_REASON_PREEMPT);
            assertEquals(ListenableWorker.Result.retry(), execution.get(5, TimeUnit.SECONDS));
            assertEquals(release, metadata.getBody());
            assertEquals(0L, metadata.getLastSuccessfulCheck());
            assertEquals(UpdatePhase.IDLE, state.load().getPhase());
            releaseServer.countDown();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void workerStoppedWhileWaitingToStartDoesNotSupersedeExistingCheck() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch coordinatorHeld = new CountDownLatch(1);
        CountDownLatch releaseCoordinator = new CountDownLatch(1);
        CountDownLatch checkReached = new CountDownLatch(1);
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        long previous = UpdateCheckCoordinator.begin();
        try {
            Future<?> holding = executor.submit(() -> UpdateCheckCoordinator.runIfCurrent(previous, () -> {
                coordinatorHeld.countDown();
                try {
                    if (!releaseCoordinator.await(10, TimeUnit.SECONDS)) throw new AssertionError("Coordinator timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }));
            assertTrue(coordinatorHeld.await(5, TimeUnit.SECONDS));
            UpdateCheckWorker worker = worker(context, "http://127.0.0.1:1/releases", () -> {
                checkReached.countDown();
                return true;
            });
            Future<ListenableWorker.Result> execution = executor.submit(() -> {
                executionThread.set(Thread.currentThread());
                return worker.doWork();
            });
            assertTrue(checkReached.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executionThread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.BLOCKED, executionThread.get().getState());
            worker.stop(WorkInfo.STOP_REASON_PREEMPT);
            releaseCoordinator.countDown();
            assertEquals(ListenableWorker.Result.retry(), execution.get(5, TimeUnit.SECONDS));
            holding.get(5, TimeUnit.SECONDS);
            assertTrue(UpdateCheckCoordinator.isCurrent(previous));
            assertEquals(0L, metadata.getLastAttempt());
        } finally {
            releaseCoordinator.countDown();
            executor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private static ServerSocket server() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(10000);
        return server;
    }

    private static void respondOutOfOrder(ServerSocket server, CountDownLatch oldArrived,
                                          CountDownLatch releaseOld, String newBody) throws Exception {
        try (Socket old = server.accept()) {
            readRequest(old);
            oldArrived.countDown();
            try (Socket newer = server.accept()) {
                readRequest(newer);
                writeResponse(newer, newBody, "new-tag");
            }
            if (!releaseOld.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to release old response");
            writeResponse(old, "[]", "old-tag");
        }
    }

    private static void readRequest(Socket socket) throws IOException {
        socket.setSoTimeout(10000);
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) { }
    }

    private static void writeResponse(Socket socket, String body, String etag) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        String headers = "HTTP/1.1 200 OK\r\nETag: " + etag + "\r\nContent-Length: " + bytes.length
            + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void expectFailure(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            fail("Expected the request to fail");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
    }

    private static UpdateCheckWorker worker(Context context) {
        return worker(context, null);
    }

    private static UpdateCheckWorker worker(Context context, String url) {
        return worker(context, url, () -> true);
    }

    private static UpdateCheckWorker worker(Context context, String url, BooleanSupplier shouldCheck) {
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context app, String name, WorkerParameters params) { return null; }
            }, (app, id, data) -> Futures.immediateFuture(null), (app, id, info) -> Futures.immediateFuture(null));
        if (url == null) return new UpdateCheckWorker(context, parameters, () -> true, () -> true);
        return new UpdateCheckWorker(context, parameters, () -> true, () -> true, shouldCheck, () -> url);
    }
}
