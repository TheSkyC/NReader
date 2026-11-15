// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

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
}
