// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import android.app.Application;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReaderPageRotationsTest {
    @Test
    public void rotationsAreIndependentAndCycleCounterclockwise() {
        ReaderPageRotations rotations = new ReaderPageRotations();
        rotations.rotate(3);
        assertEquals(270, rotations.angle(3));
        assertEquals(0, rotations.angle(4));
        rotations.rotate(3);
        assertEquals(180, rotations.angle(3));
        rotations.rotate(3);
        assertEquals(90, rotations.angle(3));
        rotations.rotate(3);
        assertEquals(0, rotations.angle(3));
    }

    @Test
    public void recreationRestoresOnlyValidEntries() {
        ReaderPageRotations rotations = new ReaderPageRotations();
        Bundle state = new Bundle();
        state.putIntArray("continuousRotationPages", new int[]{1, 2, -1, 4, 5});
        state.putIntArray("continuousRotationAngles", new int[]{270, 180, 90, 45, 90});
        rotations.restore(state, 5);
        assertEquals(270, rotations.angle(1));
        assertEquals(180, rotations.angle(2));
        assertEquals(0, rotations.angle(4));
        assertEquals(0, rotations.angle(5));
        Bundle saved = new Bundle();
        rotations.save(saved);
        ReaderPageRotations restored = new ReaderPageRotations();
        restored.restore(saved, 5);
        assertEquals(270, restored.angle(1));
        assertEquals(180, restored.angle(2));
    }

    @Test
    public void oldestRotationIsEvictedWhenCapacityIsReached() {
        ReaderPageRotations rotations = new ReaderPageRotations();
        for (int page = 0; page < 256; page++) rotations.rotate(page);
        assertEquals(0, rotations.rotate(256));
        assertEquals(0, rotations.angle(0));
        assertEquals(270, rotations.angle(256));
    }
}
