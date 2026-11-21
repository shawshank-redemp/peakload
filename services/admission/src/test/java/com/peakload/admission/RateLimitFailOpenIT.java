package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis is unreachable. The limiter must fail open: the order is still accepted, and the error is counted.
 * This class has no Redis container; its Redis points at a port nothing listens on.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitFailOpenIT extends AbstractAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();
    static final int CLOSED_REDIS_PORT = closedPort();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> CLOSED_REDIS_PORT);
    }

    @Autowired
    private MeterRegistry metrics;

    @Test
    void redisDownFailsOpenAndTheOrderIsAccepted() throws Exception {
        STUB.reply("POST", "/orders", StubOrderService.Reply.of(201, "{\"orderId\":\"o\"}"));
        double errorsBefore = metrics.counter("admission.limiter.errors").count();

        HttpResponse<String> response = send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "down-key")
                .header("X-Client-Id", "client-when-redis-down"));

        // Fails if an unreachable limiter turns into a refused order (fail closed).
        assertEquals(201, response.statusCode());
        // Fails if the failed check was not counted.
        assertTrue(metrics.counter("admission.limiter.errors").count() > errorsBefore, "the error must be counted");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
