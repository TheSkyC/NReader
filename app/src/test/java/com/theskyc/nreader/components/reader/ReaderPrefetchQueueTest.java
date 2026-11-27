package com.theskyc.nreader.components.reader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ReaderPrefetchQueueTest {
    @Test public void keepsTwoRequestsActiveAndCancelsStaleWindow() {
        List<Integer> started = new ArrayList<>();
        List<Integer> cancelled = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        ReaderPrefetchQueue queue = new ReaderPrefetchQueue((page, key, complete) -> {
            started.add(page);
            completions.add(complete);
            return () -> cancelled.add(page);
        });

        queue.update(Arrays.asList(request(1), request(2), request(3)));
        assertEquals(Arrays.asList(1, 2), started);
        assertEquals(1, queue.pendingCount());
        completions.get(0).run();
        assertEquals(Arrays.asList(1, 2, 3), started);
        queue.update(Arrays.asList(request(4)));
        assertEquals(Arrays.asList(2, 3), cancelled);
        assertEquals(1, queue.activeCount());
        assertEquals(0, queue.pendingCount());
    }

    @Test public void completedRequestIsNotStartedAgainUntilItsSourceChanges() {
        List<Integer> started = new ArrayList<>();
        List<Runnable> completions = new ArrayList<>();
        ReaderPrefetchQueue queue = new ReaderPrefetchQueue((page, key, complete) -> {
            started.add(page);
            completions.add(complete);
            return () -> { };
        });
        queue.update(Arrays.asList(request(1)));
        completions.get(0).run();
        queue.update(Arrays.asList(request(1)));
        assertEquals(Arrays.asList(1), started);
        queue.update(Arrays.asList(new ReaderPrefetchQueue.Request(1, "changed")));
        assertEquals(Arrays.asList(1, 1), started);
    }

    private static ReaderPrefetchQueue.Request request(int page) {
        return new ReaderPrefetchQueue.Request(page, "source-" + page);
    }
}
