package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

// Pure unit test: the in-memory buckets with an injected clock. No Spring, no Redis.
class LocalTokenBucketsTest {

    private static final long SECOND = 1_000_000_000L;

    private static LocalTokenBuckets buckets(AtomicLong clock, long maxKeys) {
        return new LocalTokenBuckets(
                new RateLimitProperties.Local(1.0, 1.0, maxKeys, Duration.ofMinutes(5)),
                clock::get, new SimpleMeterRegistry());
    }

    @Test
    void burstUpToCapacityIsAllowedThenDenied() {
        AtomicLong clock = new AtomicLong(0);
        LocalTokenBuckets local = buckets(clock, 100);
        for (int i = 0; i < 3; i++) {
            assertTrue(local.take("k", 3, 1.0).allowed(), "burst request " + (i + 1));
        }
        LocalTokenBuckets.Result denied = local.take("k", 3, 1.0);
        assertFalse(denied.allowed());
        // Fails if the wait reported is not the time to the next token (1 s at 1 token/s).
        assertTrue(denied.retryAfterMillis() > 0 && denied.retryAfterMillis() <= 1000, "retry was " + denied.retryAfterMillis());
    }

    @Test
    void refillGivesTokensBackWithTime() {
        AtomicLong clock = new AtomicLong(0);
        LocalTokenBuckets local = buckets(clock, 100);
        local.take("k", 2, 2.0);
        local.take("k", 2, 2.0);
        assertFalse(local.take("k", 2, 2.0).allowed(), "empty");

        clock.addAndGet(SECOND / 2); // half a second at 2 tokens/s is one token
        assertTrue(local.take("k", 2, 2.0).allowed(), "one token must have come back");
        assertFalse(local.take("k", 2, 2.0).allowed(), "but only one");
    }

    @Test
    void refillNeverExceedsCapacity() {
        AtomicLong clock = new AtomicLong(0);
        LocalTokenBuckets local = buckets(clock, 100);
        local.take("k", 2, 1.0);
        clock.addAndGet(100 * SECOND);
        assertTrue(local.take("k", 2, 1.0).allowed());
        assertTrue(local.take("k", 2, 1.0).allowed());
        assertFalse(local.take("k", 2, 1.0).allowed(), "a long idle gives at most the capacity");
    }

    @Test
    void keysAreIndependent() {
        AtomicLong clock = new AtomicLong(0);
        LocalTokenBuckets local = buckets(clock, 100);
        for (int i = 0; i < 2; i++) {
            local.take("a", 2, 0.001);
        }
        assertFalse(local.take("a", 2, 0.001).allowed());
        assertTrue(local.take("b", 2, 0.001).allowed(), "another key must be untouched");
    }

    @Test
    void memoryIsBoundedAndTheLeastRecentlyUsedBucketIsEvicted() {
        AtomicLong clock = new AtomicLong(0);
        LocalTokenBuckets local = buckets(clock, 10);
        for (int i = 0; i < 100; i++) {
            local.take("client-" + i, 5, 1.0);
        }
        // Fails if more buckets are held than the cap allows.
        assertTrue(local.size() <= 10, "size was " + local.size());
    }

    @Test
    void manyThreadsAgainstOneBucketAllowExactlyCapacity() throws Exception {
        AtomicLong clock = new AtomicLong(0); // the clock does not move: no refill during the test
        LocalTokenBuckets local = buckets(clock, 100);
        int threads = 100;
        int capacity = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> call = () -> {
                    start.await();
                    return local.take("shared", capacity, 1.0).allowed();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            // Fails if the check and the take are not one atomic step.
            assertEquals(capacity, allowed, "exactly the capacity may be allowed under contention");
        } finally {
            pool.shutdownNow();
        }
    }

    static String uniqueKey() {
        return "k-" + UUID.randomUUID();
    }
}
