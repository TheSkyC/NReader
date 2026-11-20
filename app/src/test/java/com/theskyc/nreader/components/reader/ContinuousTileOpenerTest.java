// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContinuousTileOpenerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void cancellationDuringSnapshotCopyReleasesReservation() throws Exception {
        File source = folder.newFile("page");
        Files.write(source.toPath(), new byte[128]);
        File cache = folder.newFolder("cache");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        QueueExecutor ui = new QueueExecutor();
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(cache, 128, 128)) {
            ContinuousTileOpener<ContinuousTileSnapshotStore.Lease> opener = new ContinuousTileOpener<>(worker, ui);
            try {
                AtomicInteger checks = new AtomicInteger();
                ContinuousTileOpener.Request<ContinuousTileSnapshotStore.Lease> request =
                    opener.requestCancellable(canceled -> store.create(source, () -> {
                        if (checks.incrementAndGet() == 2) {
                            copying.countDown();
                            try {
                                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return true;
                            }
                        }
                        return canceled.getAsBoolean();
                    }), value -> { throw new AssertionError(); }, failure -> { throw new AssertionError(failure); });
                assertTrue(copying.await(5, TimeUnit.SECONDS));
                opener.cancel(request);
                resume.countDown();
                worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertTrue(ui.queue.isEmpty());
                try (ContinuousTileSnapshotStore.Lease lease = store.create(source, () -> false)) {
                    assertTrue(lease.file().isFile());
                }
            } finally {
                resume.countDown();
                opener.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void servesPendingRequestsInOrderAndDisposesCanceledResult() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        Resource first = new Resource();
        ContinuousTileOpener.Request<Resource> firstRequest = opener.request(() -> {
            opened.incrementAndGet();
            return first;
        }, value -> delivered.incrementAndGet(), failure -> { throw new AssertionError(failure); });
        worker.runNext();
        opener.request(() -> {
            opened.incrementAndGet();
            return new Resource();
        }, value -> delivered.incrementAndGet(), failure -> { throw new AssertionError(failure); });
        opener.request(() -> {
            opened.incrementAndGet();
            return new Resource();
        }, value -> delivered.incrementAndGet(), failure -> { throw new AssertionError(failure); });
        opener.cancel(firstRequest);
        ui.runNext();
        assertEquals(2, worker.queue.size());
        worker.runNext();
        assertTrue(first.closed);
        worker.runNext();
        ui.runNext();
        worker.runNext();
        ui.runNext();
        assertEquals(3, opened.get());
        assertEquals(2, delivered.get());
        opener.close();
    }

    @Test
    public void closeBeforeWorkerStartsPreventsOpen() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        opener.request(() -> { throw new AssertionError("Closed request opened"); },
            value -> { throw new AssertionError(); }, failure -> { throw new AssertionError(failure); });
        opener.close();
        worker.runNext();
        assertTrue(ui.queue.isEmpty());
    }

    @Test
    public void closeDuringOpenReleasesResultWithoutDeliveringIt() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        Resource result = new Resource();
        opener.request(() -> {
            opener.close();
            return result;
        }, value -> { throw new AssertionError(); }, failure -> { throw new AssertionError(failure); });
        worker.runNext();
        assertTrue(result.closed);
        assertTrue(ui.queue.isEmpty());
    }

    @Test
    public void reportsOpenFailureAndReleasesResultWhenUiRejects() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        AtomicInteger failures = new AtomicInteger();
        opener.request(() -> { throw new IOException("Synthetic failure"); },
            value -> { throw new AssertionError(); }, failure -> failures.incrementAndGet());
        worker.runNext();
        ui.runNext();
        assertEquals(1, failures.get());
        Resource result = new Resource();
        ContinuousTileOpener<Resource> noUi = new ContinuousTileOpener<>(worker,
            command -> { throw new RejectedExecutionException(); });
        noUi.request(() -> result, value -> { throw new AssertionError(); }, failure -> { throw new AssertionError(failure); });
        worker.runNext();
        assertTrue(result.closed);
    }

    @Test
    public void queueOverflowAndWorkerRejectionRespectCancellation() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor ui = new QueueExecutor();
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        AtomicInteger failures = new AtomicInteger();
        for (int index = 0; index < 17; index++) {
            opener.request(Resource::new, value -> {}, failure -> failures.incrementAndGet());
        }
        ContinuousTileOpener.Request<Resource> rejected = opener.request(Resource::new, value -> {},
            failure -> failures.incrementAndGet());
        opener.cancel(rejected);
        ui.runNext();
        assertEquals(0, failures.get());
        opener.request(Resource::new, value -> {}, failure -> failures.incrementAndGet());
        ui.runNext();
        assertEquals(1, failures.get());
        opener.close();
        worker.runNext();

        ContinuousTileOpener<Resource> noWorker = new ContinuousTileOpener<>(
            command -> { throw new RejectedExecutionException(); }, ui);
        ContinuousTileOpener.Request<Resource> request = noWorker.request(Resource::new, value -> {},
            failure -> failures.incrementAndGet());
        noWorker.cancel(request);
        ui.runNext();
        assertEquals(1, failures.get());
    }

    @Test
    public void concurrentCancellationDoesNotWaitForOpenAndReleasesResult() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        QueueExecutor ui = new QueueExecutor();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Resource result = new Resource();
        result.onClose = closed::countDown;
        ContinuousTileOpener<Resource> opener = new ContinuousTileOpener<>(worker, ui);
        try {
            ContinuousTileOpener.Request<Resource> request = opener.request(() -> {
                opening.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return result;
            }, value -> { throw new AssertionError(); }, failure -> { throw new AssertionError(failure); });
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            caller.submit(() -> opener.cancel(request)).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(ui.queue.isEmpty());
        } finally {
            release.countDown();
            opener.close();
            worker.shutdownNow();
            caller.shutdownNow();
        }
    }

    private static final class Resource implements AutoCloseable {
        boolean closed;
        Runnable onClose;
        @Override public void close() {
            closed = true;
            if (onClose != null) onClose.run();
        }
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove().run(); }
    }
}
