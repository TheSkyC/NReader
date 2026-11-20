// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;

import com.google.common.util.concurrent.Futures;
import com.theskyc.nreader.async.update.UpdatePhase;
import com.theskyc.nreader.async.update.UpdateState;
import com.theskyc.nreader.async.update.UpdateStateStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import kotlin.coroutines.EmptyCoroutineContext;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class UpdateMetadataRetryTest {
    @Test
    public void invalidForegroundResponseDoesNotReplaceValidCache() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("[]", "old-tag");
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        state.save(UpdateState.checking(1L));
        VersionChecker checker = new VersionChecker(controller.get(), true, null, false);
        Field revision = VersionChecker.class.getDeclaredField("checkRevision");
        revision.setAccessible(true);
        revision.setLong(checker, state.revision());
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> response = serverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nETag: bad-tag\r\nContent-Length: 1\r\nConnection: close\r\n\r\n{".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            checker.requestReleases(false, true, true, "http://127.0.0.1:" + server.getLocalPort() + "/releases");
            response.get(5L, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (state.load().getPhase() == UpdatePhase.CHECKING && System.nanoTime() < deadline) Thread.sleep(10L);
            assertEquals(UpdatePhase.ERROR, state.load().getPhase());
            assertEquals("[]", metadata.getBody());
            assertEquals("old-tag", metadata.getEtag());
            assertEquals(0L, metadata.getLastSuccessfulCheck());
        } finally {
            checker.cancel();
            serverExecutor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void invalidBackgroundResponseDoesNotReplaceValidCache() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        metadata.save("[]", "old-tag");
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context app, String name, WorkerParameters params) { return null; }
            }, (app, id, data) -> Futures.immediateFuture(null), (app, id, info) -> Futures.immediateFuture(null));
        UpdateCheckWorker worker = new UpdateCheckWorker(context, parameters, () -> true, () -> true);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> response = serverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nETag: bad-tag\r\nContent-Length: 1\r\nConnection: close\r\n\r\n{".getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            try {
                worker.fetchReleaseJson(metadata, false, "http://127.0.0.1:" + server.getLocalPort() + "/releases");
                fail("Malformed release metadata must fail");
            } catch (IOException expected) {
                assertEquals("[]", metadata.getBody());
                assertEquals("old-tag", metadata.getEtag());
                assertEquals(0L, metadata.getLastSuccessfulCheck());
            }
            response.get(5L, TimeUnit.SECONDS);
        } finally {
            serverExecutor.shutdownNow();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void foregroundNotModifiedUsesCachedReleaseList() throws Exception {
        foregroundCacheScenario("[]");
    }

    @Test
    public void foregroundMissingCacheRetriesWithoutEtagAndUsesResponse() throws Exception {
        foregroundCacheScenario(null);
    }

    @Test
    public void foregroundCorruptCacheRetriesWithoutEtag() throws Exception {
        foregroundCacheScenario("{");
    }

    private void foregroundCacheScenario(String cachedBody) throws Exception {
        boolean cached = "[]".equals(cachedBody);
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        if (cachedBody != null) metadata.save(cachedBody, "test-tag");
        else context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().putString("etag", "test-tag").commit();
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        state.save(UpdateState.checking(1L));
        VersionChecker checker = new VersionChecker(controller.get(), true, null, false);
        Field revision = VersionChecker.class.getDeclaredField("checkRevision");
        revision.setAccessible(true);
        revision.setLong(checker, state.revision());
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<String[]> requests = serverExecutor.submit(() -> {
                String[] headers = new String[cached ? 1 : 2];
                for (int i = 0; i < headers.length; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        StringBuilder request = new StringBuilder();
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                        headers[i] = request.toString().toLowerCase(java.util.Locale.ROOT);
                        if (cached && i == 0) metadata.save("{", "concurrent-tag");
                        String response = i == 0 ? "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                }
                return headers;
            });
            checker.requestReleases(false, true, true, "http://127.0.0.1:" + server.getLocalPort() + "/releases");
            String[] headers = requests.get(5L, TimeUnit.SECONDS);
            assertTrue(headers[0].contains("if-none-match: test-tag"));
            if (!cached) assertFalse(headers[1].contains("if-none-match:"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (state.load().getPhase() == UpdatePhase.CHECKING && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10L);
            }
            assertEquals(UpdatePhase.IDLE, state.load().getPhase());
            assertTrue(metadata.getLastSuccessfulCheck() > 0L);
            assertEquals(cached ? "{" : "[]", metadata.getBody());
        } finally {
            checker.cancel();
            serverExecutor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void backgroundUsesCacheAndRecoversMissingCache() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context app, String name, WorkerParameters params) { return null; }
            }, (app, id, data) -> Futures.immediateFuture(null), (app, id, info) -> Futures.immediateFuture(null));
        UpdateCheckWorker worker = new UpdateCheckWorker(context, parameters, () -> true, () -> true);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<?> responses = serverExecutor.submit(() -> {
                for (int i = 0; i < 3; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        if (i == 0) metadata.save("[ ]", "concurrent-tag");
                        String response = i < 2 ? "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    }
                }
                return null;
            });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/releases";
            metadata.save("[]", "test-tag");
            assertEquals("[]", worker.fetchReleaseJson(metadata, false, url));
            assertEquals("[ ]", metadata.getBody());
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().putString("body", "{").commit();
            assertEquals("[]", worker.fetchReleaseJson(metadata, false, url));
            assertEquals("[]", metadata.getBody());
            responses.get(5L, TimeUnit.SECONDS);
        } finally {
            serverExecutor.shutdownNow();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void foregroundRepeatedNotModifiedWithoutCacheEndsInError() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class).setup();
        UpdateStateStore state = new UpdateStateStore(context);
        state.clear();
        state.save(UpdateState.checking(1L));
        VersionChecker checker = new VersionChecker(controller.get(), true, null, false);
        Field revision = VersionChecker.class.getDeclaredField("checkRevision");
        revision.setAccessible(true);
        revision.setLong(checker, state.revision());
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<Integer> responses = serverExecutor.submit(() -> {
                int count = 0;
                for (; count < 2; count++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                }
                return count;
            });
            checker.requestReleases(false, true, true, "http://127.0.0.1:" + server.getLocalPort() + "/releases");
            assertEquals(2, (int) responses.get(5L, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (state.load().getPhase() == UpdatePhase.CHECKING && System.nanoTime() < deadline) Thread.sleep(10L);
            assertEquals(UpdatePhase.ERROR, state.load().getPhase());
            assertEquals("metadata", state.load().getErrorCode());
            assertEquals(0L, new UpdateMetadataStore(context).getLastSuccessfulCheck());
        } finally {
            checker.cancel();
            serverExecutor.shutdownNow();
            state.clear();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void repeatedNotModifiedWithoutCachedBodyStopsAfterOneRetry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        UpdateMetadataStore metadata = new UpdateMetadataStore(context);
        context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().putString("etag", "test-tag").commit();
        Executor direct = Runnable::run;
        WorkerParameters parameters = new WorkerParameters(UUID.randomUUID(), Data.EMPTY, Collections.emptyList(),
            new WorkerParameters.RuntimeExtras(), 0, 0, direct, EmptyCoroutineContext.INSTANCE,
            new WorkManagerTaskExecutor(direct), new WorkerFactory() {
                @Override public ListenableWorker createWorker(Context app, String name, WorkerParameters params) {
                    return null;
                }
            }, (app, id, data) -> Futures.immediateFuture(null),
            (app, id, info) -> Futures.immediateFuture(null));
        UpdateCheckWorker worker = new UpdateCheckWorker(context, parameters, () -> true, () -> true);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<String[]> requests = serverExecutor.submit(() -> {
                String[] headers = new String[2];
                for (int i = 0; i < headers.length; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        StringBuilder request = new StringBuilder();
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                        headers[i] = request.toString();
                        socket.getOutputStream().write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                }
                return headers;
            });
            try {
                worker.fetchReleaseJson(metadata, false, "http://127.0.0.1:" + server.getLocalPort() + "/releases");
                fail("Repeated 304 without cached metadata must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("304"));
            }
            String[] headers = requests.get(5L, TimeUnit.SECONDS);
            assertTrue(headers[0].toLowerCase(java.util.Locale.ROOT).contains("if-none-match: test-tag"));
            assertFalse(headers[1].toLowerCase(java.util.Locale.ROOT).contains("if-none-match:"));
            assertEquals(0L, metadata.getLastSuccessfulCheck());
        } finally {
            serverExecutor.shutdownNow();
            context.getSharedPreferences("UpdateMetadata", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
