package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Rate limiting through admission, against a stub order service. CRITICAL is set tight for these tests: a burst
 * of 3 and one token per second, so a 429 is easy to produce and the retry wait is short.
 */
@TestPropertySource(properties = {
        "peakload.admission.rate-limit.classes.CRITICAL.capacity=3",
        "peakload.admission.rate-limit.classes.CRITICAL.refill-per-second=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIT extends AbstractRedisAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
    }

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void setUp() {
        STUB.clearReceived();
        STUB.reply("POST", "/orders", StubOrderService.Reply.of(201, "{\"orderId\":\"o\"}"));
    }

    @Test
    void overLimitIsRejectedWith429AndNothingReachesTheOrderService() throws Exception {
        String client = unique("client");
        for (int i = 0; i < 3; i++) {
            assertEquals(201, order(client, "key-" + i).statusCode(), "the burst of 3 must pass");
        }

        HttpResponse<String> limited = order(client, "key-over");

        // Fails if the fourth request in the burst was allowed through.
        assertEquals(429, limited.statusCode());
        assertTrue(limited.body().contains("rate_limited"), limited.body());
        long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        // Fails if Retry-After is not a whole number of seconds of at least 1.
        assertTrue(retryAfter >= 1, "Retry-After must be at least 1, was " + retryAfter);
        // Fails if the rejected request reached the order service.
        assertEquals(3, STUB.received().size(), "a rejected request must not reach the order service");
        assertTrue(metrics.counter("admission.rate.limited", "class", "CRITICAL").count() >= 1);
    }

    @Test
    void aSecondClientIsUnaffectedWhileTheFirstIsLimited() throws Exception {
        String abusive = unique("abusive");
        String normal = unique("normal");
        for (int i = 0; i < 3; i++) {
            order(abusive, "a-" + i);
        }
        assertEquals(429, order(abusive, "a-over").statusCode(), "the abusive client is limited");

        // Fails if one client's bucket is shared with another client.
        assertEquals(201, order(normal, "n-1").statusCode(), "a different client must be served");
    }

    @Test
    void aRejectedOrderCreatedNothingAndALaterRetryWithTheSameKeySucceeds() throws Exception {
        String client = unique("retry");
        for (int i = 0; i < 3; i++) {
            order(client, "burn-" + i);
        }
        int receivedBefore = STUB.received().size();

        HttpResponse<String> rejected = order(client, "same-key");
        assertEquals(429, rejected.statusCode());
        // Fails if the rejected POST reached the order service (it would have created an order).
        assertEquals(receivedBefore, STUB.received().size(), "nothing may reach the order service when rejected");

        // Wait as long as Retry-After says, then retry with the same Idempotency-Key.
        long retryAfter = Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow());
        Thread.sleep(retryAfter * 1000 + 200);
        HttpResponse<String> retried = order(client, "same-key");

        // Fails if the retry with the same key is refused, or if it did not reach the order service exactly once.
        assertEquals(201, retried.statusCode());
        assertEquals(receivedBefore + 1, STUB.received().size(), "the retry must reach the order service once");
        assertEquals("same-key", STUB.received().get(receivedBefore).headers().get("Idempotency-key"));
    }

    private HttpResponse<String> order(String client, String idempotencyKey) throws Exception {
        return send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Client-Id", client));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
