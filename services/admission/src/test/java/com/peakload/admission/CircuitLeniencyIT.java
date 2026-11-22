package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 4xx answers (including 409 and 422) are the order service working, so they never open the breaker. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CircuitLeniencyIT extends AbstractAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();
    private static final String ORDERS = "/orders";
    private static final String BODY = "{\"customerId\":\"c-409\",\"city\":\"Pune\",\"totalPaise\":1200,"
            + "\"pickupLat\":18.52,\"pickupLon\":73.85}";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
        registry.add("peakload.admission.rate-limit.enabled", () -> "false");
        registry.add("peakload.admission.shed.enabled", () -> "false");
        registry.add("peakload.admission.resilience.retry.enabled", () -> "false");
        registry.add("peakload.admission.resilience.circuit.enabled", () -> "true");
        registry.add("peakload.admission.resilience.circuit.window-size", () -> "4");
        registry.add("peakload.admission.resilience.circuit.minimum-calls", () -> "4");
        registry.add("peakload.admission.resilience.circuit.failure-rate-threshold", () -> "0.5");
        registry.add("peakload.admission.resilience.circuit.open-duration", () -> "PT60S");
    }

    @Test
    void conflictsAndUnprocessableAnswersNeverOpenTheBreaker() throws Exception {
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(409, "{\"error\":\"conflict\"}"));
        for (int i = 0; i < 10; i++) {
            assertEquals(409, send(request("POST", ORDERS, BODY).header("Idempotency-Key", "k-409-" + i)).statusCode());
        }
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(422, "{\"error\":\"invalid\"}"));
        for (int i = 0; i < 10; i++) {
            assertEquals(422, send(request("POST", ORDERS, BODY).header("Idempotency-Key", "k-422-" + i)).statusCode());
        }
        long received = STUB.received().stream().filter(r -> r.method().equals("POST")).count();
        assertEquals(20, received, "all 20 reached the order service: the breaker stayed closed");
    }
}
