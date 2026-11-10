// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.database;

import android.app.Application;
import android.database.MatrixCursor;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AsyncCursorQueryTest {
    @Test
    public void staleResultClosesBeforeItCanReplaceTheLatestRequest() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AsyncCursorQuery loader = new AsyncCursorQuery(work::add);
        MatrixCursor stale = cursor(), latest = cursor();
        AtomicInteger publications = new AtomicInteger();
        loader.load(() -> stale, result -> fail("Stale result published"), () -> fail("Query failed"));
        work.remove().run();
        loader.load(() -> latest, result -> publications.incrementAndGet(), () -> fail("Query failed"));
        work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(stale.isClosed());
        assertFalse(latest.isClosed());
        assertEquals(1, publications.get());
        loader.close();
        assertTrue(latest.isClosed());
    }

    @Test
    public void disposalClosesPendingAndActiveResultsAndSkipsQueuedQueries() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AsyncCursorQuery loader = new AsyncCursorQuery(work::add);
        MatrixCursor active = cursor(), pending = cursor();
        loader.load(() -> active, result -> {}, () -> fail("Query failed"));
        work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        loader.load(() -> pending, result -> fail("Disposed result published"), () -> fail("Query failed"));
        work.remove().run();
        loader.close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(active.isClosed());
        assertTrue(pending.isClosed());
        loader.load(() -> { fail("Disposed query ran"); return null; }, result -> {}, () -> {});
        assertTrue(work.isEmpty());
    }

    @Test
    public void replacementClosesPreviousCursorAndQueryFailureKeepsCurrentData() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        AsyncCursorQuery loader = new AsyncCursorQuery(work::add);
        MatrixCursor first = cursor(), second = cursor();
        loader.load(() -> first, result -> {}, () -> fail("Query failed"));
        work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        loader.load(() -> second, result -> {}, () -> fail("Query failed"));
        work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(first.isClosed());
        AtomicInteger errors = new AtomicInteger();
        loader.load(() -> { throw new IllegalStateException("Synthetic database failure"); }, result -> fail(), errors::incrementAndGet);
        work.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, errors.get());
        assertFalse(second.isClosed());
        loader.close();
    }

    private MatrixCursor cursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"id"});
        cursor.addRow(new Object[]{1});
        return cursor;
    }
}
