package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TagRowPagesTest {
    private final Queue<Runnable> work = new ArrayDeque<>();
    private final List<Integer> replacements = new ArrayList<>();
    private final List<Integer> updates = new ArrayList<>();
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final TagRowPages rows = new TagRowPages(work::add);

    @Test public void cachedRowsRequireNoCursorWorkAndFarRowsWaitForWorker() {
        MatrixCursor cursor = load("current", 250);
        complete();
        assertEquals(250, rows.size());
        int loadedThrough = cursor.getPosition();
        assertEquals(1, rows.get(0).tag.getId());
        assertEquals("translated 0", rows.get(0).displayName);
        assertNull(rows.get(129));
        assertNull(rows.get(130));
        assertEquals(1, work.size());
        assertEquals(loadedThrough, cursor.getPosition());
        complete();
        assertEquals(130, rows.get(129).tag.getId());
        assertEquals(Arrays.asList(128), updates);
        rows.close();
        assertFalse(cursor.isClosed());
        complete();
        assertTrue(cursor.isClosed());
    }

    @Test public void newSearchAndCloseDiscardQueuedPageResults() {
        MatrixCursor former = load("old", 200);
        complete();
        assertNull(rows.get(129));
        work.remove().run();
        MatrixCursor latest = load("new", 200);
        idleMain();
        assertTrue(updates.isEmpty());
        complete();
        assertTrue(former.isClosed());
        assertEquals("new 0", rows.get(0).tag.getName());
        assertNull(rows.get(129));
        work.remove().run();
        rows.close();
        complete();
        assertTrue(latest.isClosed());
        assertTrue(updates.isEmpty());
        assertEquals(0, rows.size());
    }

    @Test public void invalidAccountClearsPreviouslyPublishedRowsAndDropsPage() {
        MatrixCursor cursor = load("old account", 200);
        complete();
        assertEquals("old account 0", rows.get(0).tag.getName());
        assertNull(rows.get(129));
        work.remove().run();
        allowed.set(false);
        idleMain();
        assertTrue(updates.isEmpty());
        assertEquals(Arrays.asList(200, 0), replacements);
        assertEquals(0, rows.size());
        assertNull(rows.get(0));
        complete();
        assertTrue(cursor.isClosed());
    }

    @Test public void cacheRemainsBoundedAsNewPagesAreVisited() {
        load("large", 700);
        complete();
        for (int index = 64; index < 700; index += 64) {
            assertNull(rows.get(index));
            complete();
            assertEquals(index + 1, rows.get(index).tag.getId());
        }
        assertNull(rows.get(0));
        complete();
        assertEquals(1, rows.get(0).tag.getId());
        rows.close();
        complete();
    }

    private MatrixCursor load(String name, int count) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"idTag", "name", "type", "count", "status", "displayName"});
        for (int index = 0; index < count; index++) {
            cursor.addRow(new Object[]{index + 1, name + " " + index, 3, 50, 0, "translated " + index});
        }
        rows.load(() -> cursor, allowed::get, (previous, current) -> replacements.add(current),
            (start, size) -> updates.add(start));
        return cursor;
    }

    private void complete() {
        while (!work.isEmpty()) work.remove().run();
        idleMain();
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
