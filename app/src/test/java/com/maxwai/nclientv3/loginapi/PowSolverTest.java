package com.maxwai.nclientv3.loginapi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;

public class PowSolverTest {
    @Test
    public void solvesAndVerifiesNonByteAlignedDifficulty() throws Exception {
        String nonce = PowSolver.solve("example", 13, 10000, () -> false);
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(("example" + nonce).getBytes(StandardCharsets.UTF_8));
        assertTrue(PowSolver.hasLeadingZeroBits(digest, 13));
    }

    @Test
    public void checksBitBoundaries() {
        byte[] hash = {0, 0x07};
        assertTrue(PowSolver.hasLeadingZeroBits(hash, 13));
        assertFalse(PowSolver.hasLeadingZeroBits(hash, 14));
        assertEquals("0", PowSolver.solve("x", 0, 1000, () -> false));
        assertThrows(IllegalArgumentException.class, () -> PowSolver.hasLeadingZeroBits(hash, 17));
        assertThrows(IllegalArgumentException.class, () -> PowSolver.solve("x", 257, 1000, () -> false));
    }

    @Test
    public void cancelsBeforeDoingWork() {
        assertThrows(CancellationException.class, () -> PowSolver.solve("x", 256, 1000, () -> true));
    }

    @Test
    public void cancellationIsObservedWhileSearching() {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CancellationException.class,
            () -> PowSolver.solve("fixture-challenge", 256, 10000, () -> checks.incrementAndGet() >= 2));
        assertTrue(checks.get() >= 2);
    }

    @Test
    public void difficultChallengeStopsAtDeadline() {
        assertThrows(CancellationException.class, () -> PowSolver.solve("fixture-challenge", 256, 1, () -> false));
    }
}
