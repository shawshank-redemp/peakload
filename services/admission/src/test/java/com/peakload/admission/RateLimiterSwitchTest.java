package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * The switching logic, with a fake Redis that can fail or block, and a clock the test moves by hand.
 * No Spring, no Redis: these tests are fast and deterministic.
 */
class RateLimiterSwitchTest {

    private static final long COOL_DOWN_MILLIS = 2000;

    /** A Redis stand-in. It counts calls, can fail, and can block a call until released. */
    static final class FakeRedis implements RedisBuckets {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;
        volatile CountDownLatch blockUntil; // when set, the call waits on this latch before answering

        @Override
        public List<Long> take(String key, long capacity, double refillPerSecond) {
            calls.incrementAndGet();
            CountDownLatch latch = blockUntil;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failing) {
                throw new QueryTimeoutException("fake Redis does not answer");
            }
            return List.of(1L, 0L);
        }
    }

    private final AtomicLong clock = new AtomicLong(0);
    private final FakeRedis redis = new FakeRedis();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimiter limiter = build(true, COOL_DOWN_MILLIS);

    private RateLimiter build(boolean breakerEnabled, long coolDownMillis) {
        RateLimitProperties properties = new RateLimitProperties(true,
                Map.of(RequestClass.CRITICAL, new RateLimitProperties.Bucket(1000, 1000.0),
                        RequestClass.STANDARD, new RateLimitProperties.Bucket(1000, 1000.0),
                        RequestClass.LOW, new RateLimitProperties.Bucket(1000, 1000.0)),
                new RateLimitProperties.CircuitBreaker(breakerEnabled, Duration.ofMillis(coolDownMillis)),
                new RateLimitProperties.Local(1.0, 1.0, 50_000, Duration.ofMinutes(5)));
        LimiterCircuit circuit = new LimiterCircuit(properties.circuitBreaker(), clock::get, registry);
        LocalTokenBuckets local = new LocalTokenBuckets(properties.local(), clock::get, registry);
        return new RateLimiter(redis, local, circuit, properties, registry);
    }

    private void advanceMillis(long millis) {
        clock.addAndGet(millis * 1_000_000L);
    }

    private double mode() {
        return registry.get("admission.limiter.mode").gauge().value();
    }

    @Test
    void aRedisErrorOpensTheCircuitAndTheRequestIsDecidedLocally() {
        redis.failing = true;

        RateLimiter.Decision decision = limiter.check("c", RequestClass.CRITICAL);

        // Fails if the failed request was not decided by the local bucket (it has a full bucket: allowed).
        assertTrue(decision.allowed());
        assertEquals(1.0, mode(), "the mode gauge must show local");
        assertEquals(1.0, registry.counter("admission.limiter.circuit.opens").count());
    }

    @Test
    void duringTheCoolDownRedisIsNotCalled() {
        redis.failing = true;
        limiter.check("c", RequestClass.CRITICAL);
        int callsAfterFailure = redis.calls.get();

        advanceMillis(COOL_DOWN_MILLIS - 1);
        for (int i = 0; i < 20; i++) {
            limiter.check("c", RequestClass.CRITICAL);
        }
        // Fails if requests during the cool-down still go to Redis (each would wait for the timeout).
        assertEquals(callsAfterFailure, redis.calls.get(), "no Redis call may happen during the cool-down");
    }

    @Test
    void aSuccessfulProbeRestoresRedisMode() {
        redis.failing = true;
        limiter.check("c", RequestClass.CRITICAL);
        advanceMillis(COOL_DOWN_MILLIS);
        redis.failing = false;

        limiter.check("c", RequestClass.CRITICAL); // the probe
        // Fails if a successful probe does not close the circuit.
        assertEquals(0.0, mode(), "mode must return to Redis after a successful probe");
        int before = redis.calls.get();
        limiter.check("c", RequestClass.CRITICAL);
        assertEquals(before + 1, redis.calls.get(), "after recovery, requests use Redis again");
    }

    @Test
    void aFailedProbeReopensTheCircuitForAnotherCoolDown() {
        redis.failing = true;
        limiter.check("c", RequestClass.CRITICAL);
        advanceMillis(COOL_DOWN_MILLIS);
        int callsBeforeProbe = redis.calls.get();

        limiter.check("c", RequestClass.CRITICAL); // probe, fails
        // Fails if the failed probe does not count as one Redis call and does not re-open the circuit.
        assertEquals(callsBeforeProbe + 1, redis.calls.get());
        assertEquals(1.0, mode());
        assertEquals(2.0, registry.counter("admission.limiter.circuit.opens").count(),
                "the failed probe is a second open");

        int callsAfterReopen = redis.calls.get();
        advanceMillis(COOL_DOWN_MILLIS - 1);
        limiter.check("c", RequestClass.CRITICAL);
        assertEquals(callsAfterReopen, redis.calls.get(), "a new cool-down must start after a failed probe");
    }

    @Test
    void onlyOneProbeIsInFlightAtATime() throws Exception {
        redis.failing = true;
        limiter.check("c", RequestClass.CRITICAL);
        advanceMillis(COOL_DOWN_MILLIS);
        redis.failing = false;
        CountDownLatch release = new CountDownLatch(1);
        redis.blockUntil = release;

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RateLimiter.Decision> probe = pool.submit(() -> limiter.check("c", RequestClass.CRITICAL));
            // Wait until the probe is inside Redis (its call has been counted).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (redis.calls.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            int callsWithProbeInFlight = redis.calls.get();

            // Fails if a second request also goes to Redis while the probe is in flight.
            limiter.check("c", RequestClass.CRITICAL);
            limiter.check("c", RequestClass.CRITICAL);
            assertEquals(callsWithProbeInFlight, redis.calls.get(), "only one probe at a time");

            release.countDown();
            assertTrue(probe.get(10, TimeUnit.SECONDS).allowed());
            assertEquals(0.0, mode(), "the probe's success restores Redis mode");
        } finally {
            redis.blockUntil = null;
            pool.shutdownNow();
        }
    }

    @Test
    void withTheBreakerDisabledEveryRequestCallsRedis() {
        RateLimiter noBreaker = build(false, COOL_DOWN_MILLIS);
        redis.failing = true;

        for (int i = 0; i < 5; i++) {
            noBreaker.check("c", RequestClass.CRITICAL);
        }
        // Fails if the disabled breaker still stops calling Redis.
        assertEquals(5, redis.calls.get(), "each request must call Redis when the breaker is off");
        assertEquals(0.0, registry.counter("admission.limiter.circuit.opens").count());
        assertFalse(registry.get("admission.limiter.mode").gauge().value() > 0);
    }
}
