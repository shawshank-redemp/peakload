package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The order breaker through admission, with the order service stubbed. Retries are off here. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CircuitIT extends AbstractAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();
    private static final String ORDERS = "/orders";
    private static final String BODY = "{\"customerId\":\"c-circuit\",\"city\":\"Pune\",\"totalPaise\":1200,"
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
        registry.add("peakload.admission.resilience.circuit.open-duration", () -> "PT2S");
        registry.add("peakload.admission.resilience.circuit.half-open-probes", () -> "1");
    }

    private HttpResponse<String> postOrder(String key) throws Exception {
        return send(request("POST", ORDERS, BODY).header("Idempotency-Key", key));
    }

    private long ordersReceived() {
        return STUB.received().stream().filter(r -> r.method().equals("POST")).count();
    }

    @Test
    void opensOnUpstream503AndRecoversAfterTheCoolDown() throws Exception {
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(503, "{\"error\":\"down\"}"));
        for (int i = 0; i < 4; i++) {
            assertEquals(503, postOrder("k-open-" + i).statusCode(), "upstream 503s are returned as they are");
        }
        assertEquals(4, ordersReceived());

        HttpResponse<String> blocked = postOrder("k-blocked");
        assertEquals(503, blocked.statusCode());
        assertTrue(blocked.body().contains("order_unavailable"), blocked.body());
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent(), "Retry-After must be set");
        assertEquals(4, ordersReceived(), "open breaker: the order service was not called");

        Thread.sleep(2100); // the 2 s cool-down
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(201, "{\"orderId\":\"o-probe\"}"));
        assertEquals(201, postOrder("k-probe").statusCode(), "the half-open probe reaches the order service");
        assertEquals(5, ordersReceived());
        assertEquals(201, postOrder("k-after").statusCode(), "closed again after a successful probe");
        assertEquals(6, ordersReceived());
    }
}
