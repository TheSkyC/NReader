// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.components.reader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContinuousTileSnapshotStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void failedDeletionRetainsBudgetAndIsRetriedOnNextCreate() throws Exception {
        File cache = folder.newFolder("cache");
        File source = folder.newFile("page");
        Files.write(source.toPath(), new byte[] {1});
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(cache, 8, 8)) {
            ContinuousTileSnapshotStore.Lease lease = store.create(source, () -> false);
            File snapshot = lease.file();
            assertTrue(snapshot.delete());
            assertTrue(snapshot.mkdir());
            File blocker = new File(snapshot, "blocker");
            assertTrue(blocker.createNewFile());
            assertThrows(IOException.class, lease::close);
            assertThrows(IllegalStateException.class, lease::file);
            assertThrows(ContinuousTileSnapshotStore.BudgetExceededException.class, () -> store.create(source, () -> false));
            assertTrue(blocker.delete());
            try (ContinuousTileSnapshotStore.Lease next = store.create(source, () -> false)) {
                assertTrue(next.file().isFile());
            }
            assertFalse(snapshot.exists());
            lease.close();
        }
    }

    @Test
    public void budgetAndLockAreHeldUntilLeaseCloses() throws Exception {
        File cache = folder.newFolder("cache");
        File source = folder.newFile("page");
        Files.write(source.toPath(), new byte[] {1});
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(cache, 8, 8)) {
            assertThrows(IOException.class, () -> ContinuousTileSnapshotStore.open(cache, 8, 8));
            ContinuousTileSnapshotStore.Lease lease = store.create(source, () -> false);
            assertThrows(IOException.class, () -> store.create(source, () -> false));
            assertThrows(IOException.class, store::close);
            File snapshot = lease.file();
            lease.close();
            lease.close();
            assertFalse(snapshot.exists());
            try (ContinuousTileSnapshotStore.Lease next = store.create(source, () -> false)) {
                assertTrue(next.file().isFile());
            }
        }
        try (ContinuousTileSnapshotStore ignored = ContinuousTileSnapshotStore.open(cache, 8, 8)) {
            assertTrue(cache.isDirectory());
        }
    }

    @Test
    public void failedAndInterruptedCopiesReleaseBudget() throws Exception {
        File cache = folder.newFolder("cache");
        File source = folder.newFile("page");
        Files.write(source.toPath(), new byte[] {1, 2});
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(cache, 1, 1)) {
            assertThrows(IOException.class, () -> store.create(source, () -> false));
            assertThrows(IOException.class, () -> store.create(source, () -> true));
            assertThrows(AssertionError.class, () -> store.create(source, () -> { throw new AssertionError(); }));
            Files.write(source.toPath(), new byte[] {1});
            try (ContinuousTileSnapshotStore.Lease lease = store.create(source, () -> false)) {
                assertTrue(lease.file().isFile());
            }
        }
    }

    @Test
    public void concurrentCopyReservesBudgetBeforeOpeningSource() throws Exception {
        File cache = folder.newFolder("cache");
        File source = folder.newFile("page");
        Files.write(source.toPath(), new byte[] {1});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ContinuousTileSnapshotStore store = ContinuousTileSnapshotStore.open(cache, 8, 8)) {
            Future<ContinuousTileSnapshotStore.Lease> pending = executor.submit(() -> store.create(source, () -> {
                entered.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return true;
                }
                return false;
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(IOException.class, () -> store.create(source, () -> false));
                assertThrows(IOException.class, store::close);
            } finally {
                resume.countDown();
            }
            try (ContinuousTileSnapshotStore.Lease lease = pending.get(10, TimeUnit.SECONDS)) {
                assertTrue(lease.file().isFile());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reopenRemovesOnlyMatchingStaleFiles() throws Exception {
        File cache = folder.newFolder("cache");
        File stale = new File(cache, "reader-tile-old.snapshot");
        File other = new File(cache, "other.snapshot");
        assertTrue(stale.createNewFile());
        assertTrue(other.createNewFile());
        try (ContinuousTileSnapshotStore ignored = ContinuousTileSnapshotStore.open(cache, 8, 8)) {
            assertFalse(stale.exists());
            assertTrue(other.isFile());
        }
        assertTrue(stale.mkdir());
        assertThrows(IOException.class, () -> ContinuousTileSnapshotStore.open(cache, 8, 8));
        assertTrue(stale.isDirectory());
    }
}
