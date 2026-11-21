package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * Redis is paused (it stops answering), then unpaused. While it is down, rate limiting must still hold, from the
 * local buckets: an over-limit client gets 429 and a normal client is served. After Redis returns, the limiter goes
 * back to Redis, which is checked by the bucket key reappearing in Redis.
 *
 * <p>CRITICAL is tight (burst 3, one token per second), and the cool-down is short, so the switch is quick to see.
 */
@TestPropertySource(properties = {
        "peakload.admission.rate-limit.classes.CRITICAL.capacity=3",
        "peakload.admission.rate-limit.classes.CRITICAL.refill-per-second=1",
        "peakload.admission.rate-limit.circuit-breaker.cool-down=PT0.3S"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitOutageIT extends AbstractRedisAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
    }

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        STUB.clearReceived();
        STUB.reply("POST", "/orders", StubOrderService.Reply.of(201, "{\"orderId\":\"o\"}"));
    }

    @Test
    void redisOutageKeepsLimitingLocallyAndRecoveryReturnsToRedis() throws Exception {
        String normal = unique("normal");
        String abusive = unique("abusive");
        assertEquals(201, order(normal, "warm").statusCode(), "Redis is up: normal request served");
        assertEquals(0.0, metrics.get("admission.limiter.mode").gauge().value(), "starts in Redis mode");

        pauseRedis();
        try {
            // The first call during the outage times out in Redis and is decided locally.
            assertEquals(201, order(abusive, "a-1").statusCode());
            assertEquals(201, order(abusive, "a-2").statusCode());
            assertEquals(201, order(abusive, "a-3").statusCode());
            HttpResponse<String> limited = order(abusive, "a-4");

            // Fails if the local limiter does not enforce the limit while Redis is down.
            assertEquals(429, limited.statusCode(), "the abusive client is limited by the local buckets");
            assertTrue(limited.headers().firstValue("Retry-After").isPresent());
            // Fails if a normal client is refused because of the outage.
            assertEquals(201, order(unique("other"), "n-1").statusCode(), "a normal client is served during the outage");
            // Fails if the limiter still reports Redis mode during the outage.
            assertEquals(1.0, metrics.get("admission.limiter.mode").gauge().value(), "mode must be local");
            assertTrue(metrics.counter("admission.limiter.fallback", "outcome", "limited").count() >= 1);
        } finally {
            unpauseRedis();
        }

        // Fails if the limiter does not return to Redis after recovery: a probe must succeed and close the circuit.
        String recovered = unique("recovered");
        awaitTrue("limiter back in Redis mode", Duration.ofSeconds(20), () -> {
            try {
                order(recovered, "probe-" + UUID.randomUUID());
            } catch (Exception e) {
                throw new IllegalStateException("probe request failed", e);
            }
            return metrics.get("admission.limiter.mode").gauge().value() == 0.0;
        });
        // Fails if the bucket was not written to Redis after recovery.
        assertTrue(Boolean.TRUE.equals(redis.hasKey("rl:" + recovered + ":CRITICAL")),
                "the recovered client's bucket must exist in Redis again");
    }

    private HttpResponse<String> order(String client, String idempotencyKey) throws Exception {
        return send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Client-Id", client));
    }

    private static void pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void awaitTrue(String what, Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("timed out after " + timeout + " waiting for: " + what);
            }
            Thread.sleep(200);
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
