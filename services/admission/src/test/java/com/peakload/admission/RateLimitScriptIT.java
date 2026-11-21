package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The token-bucket script and the limiter, against a real Redis 7 (Testcontainers, random port). These are
 * not Spring-context tests; they talk to Redis directly.
 */
class RateLimitScriptIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    @BeforeAll
    static void start() {
        REDIS.start();
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        connections.destroy();
        REDIS.stop();
    }

    /** Fresh key per test, so tests do not share buckets. */
    private static String fresh() {
        return "rl:test-" + UUID.randomUUID() + ":CRITICAL";
    }

    private static List<Long> take(String key, long capacity, double refill) {
        return TokenBucketScript.take(redis, key, capacity, refill);
    }

    @Test
    void burstUpToCapacityIsAllowedThenDenied() {
        String key = fresh();
        for (int i = 0; i < 5; i++) {
            assertEquals(1L, take(key, 5, 1.0).get(0), "request " + (i + 1) + " of the burst must be allowed");
        }
        List<Long> denied = take(key, 5, 1.0);
        // Fails if a sixth request inside the burst window gets through.
        assertEquals(0L, denied.get(0));
        assertTrue(denied.get(1) > 0, "a denied request must report a wait");
    }

    @Test
    void refillAllowsAgainAfterTheRightInterval() throws InterruptedException {
        String key = fresh();
        // 20 tokens per second: one token every 50 ms.
        take(key, 2, 20.0);
        take(key, 2, 20.0);
        assertEquals(0L, take(key, 2, 20.0).get(0), "the bucket is empty");

        Thread.sleep(120); // two refill intervals
        // Fails if the bucket never refills, or refills too slowly.
        assertEquals(1L, take(key, 2, 20.0).get(0), "a token must come back after the refill interval");
    }

    @Test
    void retryAfterIsTheWaitForTheNextToken() {
        String key = fresh();
        take(key, 1, 4.0); // one token, refill 4/s: the next token is 250 ms away
        List<Long> denied = take(key, 1, 4.0);

        assertEquals(0L, denied.get(0));
        // Fails if the reported wait is not the time to the next token (at most 250 ms, at least 1 ms).
        assertTrue(denied.get(1) >= 1 && denied.get(1) <= 250, "retry_ms was " + denied.get(1));
    }

    @Test
    void twoKeysAreIndependent() {
        String a = fresh();
        String b = fresh();
        for (int i = 0; i < 3; i++) {
            take(a, 3, 0.001);
        }
        // Fails if draining one bucket affects another.
        assertEquals(0L, take(a, 3, 0.001).get(0));
        assertEquals(1L, take(b, 3, 0.001).get(0));
    }

    @Test
    void classesAreIndependentForTheSameClient() {
        String client = "client-" + UUID.randomUUID();
        RateLimitProperties properties = new RateLimitProperties(true, Map.of(
                RequestClass.CRITICAL, new RateLimitProperties.Bucket(2, 0.001),
                RequestClass.STANDARD, new RateLimitProperties.Bucket(2, 0.001),
                RequestClass.LOW, new RateLimitProperties.Bucket(2, 0.001)));
        RateLimiter limiter = limiterFor(redis, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        limiter.check(client, RequestClass.CRITICAL);
        limiter.check(client, RequestClass.CRITICAL);
        // Fails if CRITICAL being used up also uses up STANDARD for the same client.
        assertFalse(limiter.check(client, RequestClass.CRITICAL).allowed(), "CRITICAL must be exhausted");
        assertTrue(limiter.check(client, RequestClass.STANDARD).allowed(), "STANDARD for the same client must be untouched");
    }

    @Test
    void theKeyHasAnExpiry() {
        String key = fresh();
        // Capacity 10 at 5/s: refill from empty takes 2 s, so the expiry should be about 4 s.
        take(key, 10, 5.0);
        Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);

        // Fails if the key never expires (an idle client would leave state behind forever).
        assertTrue(ttlMillis != null && ttlMillis > 0 && ttlMillis <= 4_000,
                "expiry must be set to about twice the refill time, was " + ttlMillis);
    }

    @Test
    void manyThreadsAgainstOneBucketAllowExactlyCapacity() throws Exception {
        String key = fresh();
        int capacity = 50;
        int threads = 200;
        // Rate 0.001 tokens/s: no refill happens during the test, so the only tokens are the initial capacity.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Long> call = () -> {
                    startGate.await();
                    return take(key, capacity, 0.001).get(0);
                };
                futures.add(pool.submit(call));
            }
            startGate.countDown();
            long allowed = 0;
            for (Future<Long> future : futures) {
                allowed += future.get(60, TimeUnit.SECONDS);
            }
            // Fails if the check and the take were not one atomic step: two threads could both see a token.
            assertEquals(capacity, allowed, "exactly the capacity may be allowed under contention");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void redisErrorFailsOpenAndIsCounted() {
        // A limiter pointing at a closed port must allow the request, and count the error.
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", closedPort());
        dead.afterPropertiesSet();
        StringRedisTemplate deadRedis = new StringRedisTemplate(dead);
        deadRedis.afterPropertiesSet();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RateLimitProperties properties = new RateLimitProperties(true, Map.of(
                RequestClass.CRITICAL, new RateLimitProperties.Bucket(1, 1),
                RequestClass.STANDARD, new RateLimitProperties.Bucket(1, 1),
                RequestClass.LOW, new RateLimitProperties.Bucket(1, 1)));
        RateLimiter limiter = limiterFor(deadRedis, properties, registry);
        try {
            assertTrue(limiter.check("c", RequestClass.CRITICAL).allowed(), "fail open: the request must be allowed");
            // Fails if the error is not counted.
            assertEquals(1.0, registry.counter("admission.limiter.errors").count());
        } finally {
            dead.destroy();
        }
    }

    /** The limiter wired the way Spring wires it, around the given Redis template. */
    static RateLimiter limiterFor(StringRedisTemplate template, RateLimitProperties properties,
                                  io.micrometer.core.instrument.MeterRegistry registry) {
        LimiterCircuit circuit = new LimiterCircuit(properties, registry);
        return new RateLimiter(new StringRedisBuckets(template), new LocalTokenBuckets(properties, registry),
                circuit, properties, registry);
    }

    private static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
