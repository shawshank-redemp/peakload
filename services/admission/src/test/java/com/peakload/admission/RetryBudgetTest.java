package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);

    private RetryBudget budget() {
        return new RetryBudget(0.10, Duration.ofSeconds(10), nanos::get);
    }

    @Test
    void retriesAreCappedAtTheShareOfCalls() {
        RetryBudget budget = budget();
        for (int i = 0; i < 100; i++) {
            budget.recordCall();
        }
        int granted = 0;
        for (int i = 0; i < 50; i++) {
            if (budget.tryRetry()) {
                granted++;
            }
        }
        assertEquals(10, granted, "10% of 100 calls is 10 retries, no more");
    }

    @Test
    void noCallsMeansNoRetries() {
        assertFalse(budget().tryRetry());
    }

    @Test
    void countsLeaveTheWindowAfterItPasses() {
        RetryBudget budget = budget();
        for (int i = 0; i < 100; i++) {
            budget.recordCall();
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(budget.tryRetry());
        }
        assertFalse(budget.tryRetry(), "budget used up in this window");
        nanos.addAndGet(11_000_000_000L); // past the 10 s window
        assertFalse(budget.tryRetry(), "the old calls have left the window, so there is no budget at all");
        for (int i = 0; i < 20; i++) {
            budget.recordCall();
        }
        assertTrue(budget.tryRetry(), "new calls give new budget");
    }
}
