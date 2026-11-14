package com.maxwai.nclientv3.components;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class GalleryOpenGateTest {
    @After
    public void releaseGate() {
        GalleryOpenGate.release();
    }

    @Test
    public void acceptsOnlyOneGalleryOpenUntilReleased() {
        assertTrue(GalleryOpenGate.tryAcquire(42));
        assertFalse(GalleryOpenGate.tryAcquire(42));
        assertFalse(GalleryOpenGate.tryAcquire(84));

        GalleryOpenGate.release();

        assertTrue(GalleryOpenGate.tryAcquire(84));
    }

    @Test
    public void rejectsInvalidIds() {
        assertFalse(GalleryOpenGate.tryAcquire(0));
        assertFalse(GalleryOpenGate.tryAcquire(-1));
    }
}
