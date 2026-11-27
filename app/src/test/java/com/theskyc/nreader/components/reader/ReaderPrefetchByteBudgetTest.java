package com.theskyc.nreader.components.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ReaderPrefetchByteBudgetTest {
    @Test public void consumedBytesAccumulateAcrossLimitChangesAndCloseTheSession() {
        ReaderPrefetchByteBudget budget = new ReaderPrefetchByteBudget(16);
        String token = budget.token();
        assertSame(budget, ReaderPrefetchByteBudget.find(token));
        assertEquals(16, budget.remainingBytes());

        budget.recordConsumed(6);
        budget.setLimit(8);
        assertEquals(2, budget.remainingBytes());
        budget.setLimit(32);
        assertEquals(26, budget.remainingBytes());
        assertEquals(6, budget.consumedBytes());

        budget.close();
        assertEquals(0, budget.remainingBytes());
        assertNull(ReaderPrefetchByteBudget.find(token));
    }
}
