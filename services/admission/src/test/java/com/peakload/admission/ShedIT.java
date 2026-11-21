package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Load shedding through admission, with the order service stubbed as slow or fast. Latency drives the level here:
 * the in-flight thresholds are set out of reach so the test is about the latency signal.
 *
 * <p>Thresholds for this test (not the config defaults): latency ELEVATED 200/100 ms, HIGH 600/300, HARD_CAP 1500/900;
 * the EWMA weight is 0.5 and it halves every second. The rate limiter is off, so requests are not limited by client.
 *
 * <p>Each phase sets the stub's delay for both the order GET and the order POST to the same value, so every served
 * upstream call samples that delay. A phase warms the EWMA with served POSTs until its level is reached, then
 * checks which classes are shed. Shed requests make no upstream call, so they add no sample.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShedIT extends AbstractAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();
    private static final String MENU = "/browse/restaurants/" + SPICE_ROUTE + "/menu";
    private static final String ORDER = "/orders/o-1";

    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
        registry.add("peakload.admission.upstream.read-timeout", () -> "PT5S");
        registry.add("peakload.admission.rate-limit.enabled", () -> "false");
        registry.add("peakload.admission.shed.enabled", () -> "true");
        registry.add("peakload.admission.shed.smoothing", () -> "0.5");
        registry.add("peakload.admission.shed.latency-half-life", () -> "PT1S");
        // In-flight thresholds out of reach: latency decides the level in this test.
        registry.add("peakload.admission.shed.elevated.in-flight-enter", () -> "0.9");
        registry.add("peakload.admission.shed.elevated.in-flight-exit", () -> "0.8");
        registry.add("peakload.admission.shed.high.in-flight-enter", () -> "0.95");
        registry.add("peakload.admission.shed.high.in-flight-exit", () -> "0.9");
        registry.add("peakload.admission.shed.hard-cap.in-flight-enter", () -> "0.99");
        registry.add("peakload.admission.shed.hard-cap.in-flight-exit", () -> "0.97");
        registry.add("peakload.admission.shed.elevated.latency-enter-ms", () -> "200");
        registry.add("peakload.admission.shed.elevated.latency-exit-ms", () -> "100");
        registry.add("peakload.admission.shed.high.latency-enter-ms", () -> "600");
        registry.add("peakload.admission.shed.high.latency-exit-ms", () -> "300");
        registry.add("peakload.admission.shed.hard-cap.latency-enter-ms", () -> "1500");
        registry.add("peakload.admission.shed.hard-cap.latency-exit-ms", () -> "900");
    }

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void resetStub() {
        STUB.clearReceived();
        setDelay(0);
    }

    @Test
    void shedsLowFirstThenStandardThenCriticalAsLatencyRises_andRecovers() throws Exception {
        // NORMAL: every class is served. Fast calls keep the smoothed latency near zero.
        assertEquals(200, send(request("GET", MENU, null)).statusCode());
        assertEquals(200, send(request("GET", ORDER, null)).statusCode());
        assertEquals(201, createOrder("normal").statusCode());

        // ELEVATED: 300 ms calls. Two served POSTs bring the smoothed value from 0 to 225 ms (above 200).
        setDelay(300);
        assertEquals(201, createOrder("elevated-1").statusCode());
        assertEquals(201, createOrder("elevated-2").statusCode());
        assertShed(send(request("GET", MENU, null)), "LOW", "ELEVATED");
        assertEquals(200, send(request("GET", ORDER, null)).statusCode(), "STANDARD is served at ELEVATED");
        assertEquals(201, createOrder("elevated-3").statusCode(), "CRITICAL is served at ELEVATED");

        // HIGH: 700 ms calls. Five served POSTs bring the value above 600 ms. LOW and STANDARD are shed.
        setDelay(700);
        for (int i = 1; i <= 5; i++) {
            assertEquals(201, createOrder("high-warm-" + i).statusCode());
        }
        assertShed(send(request("GET", MENU, null)), "LOW", "HIGH");
        assertShed(send(request("GET", ORDER, null)), "STANDARD", "HIGH");
        assertEquals(201, createOrder("high-served").statusCode(), "CRITICAL is served at HIGH");

        // HARD_CAP: one 3 s call takes the value past 1500 ms. CRITICAL is then rejected at once.
        setDelay(3000);
        assertEquals(201, createOrder("hardcap-raise").statusCode(), "the call that raises the level is still served");
        int upstreamBefore = STUB.received().size();
        long started = System.nanoTime();
        HttpResponse<String> rejected = createOrder("hardcap-rejected");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertShed(rejected, "CRITICAL", "HARD_CAP");
        assertTrue(elapsedMillis < 1000, "a hard-cap rejection must be immediate, took " + elapsedMillis + " ms");
        // Fails if a shed request reached the order service.
        assertEquals(upstreamBefore, STUB.received().size(), "a shed request must not reach the order service");
        assertTrue(metrics.counter("admission.shed", "class", "CRITICAL").count() >= 1);

        // Recovery: the stub is fast again. No upstream calls are made while shedding, so the value fades
        // (half-life 1 s) and the level falls back. The first served request brings it down further.
        setDelay(0);
        long deadline = System.currentTimeMillis() + 20_000;
        while (createOrder("recover").statusCode() != 201) {
            assertTrue(System.currentTimeMillis() < deadline, "CRITICAL was not served again within 20 s");
            Thread.sleep(200);
        }
        while (send(request("GET", MENU, null)).statusCode() != 200) {
            assertTrue(System.currentTimeMillis() < deadline, "LOW was not served again within 20 s");
            Thread.sleep(200);
        }
        assertEquals(200, send(request("GET", ORDER, null)).statusCode(), "STANDARD is served again");
    }

    /** Sets the stub's delay for the order GET and POST together, so every served upstream call samples it. */
    private void setDelay(long millis) {
        STUB.reply("POST", "/orders", new StubOrderService.Reply(201, Map.of(), "{\"orderId\":\"o-1\"}", millis));
        STUB.reply("GET", ORDER, new StubOrderService.Reply(200, Map.of(), "{\"orderId\":\"o-1\"}", millis));
    }

    private HttpResponse<String> createOrder(String key) throws Exception {
        return send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key + "-" + System.nanoTime())
                .header("X-Client-Id", "shed-test"));
    }

    /** Checks the 503, Retry-After, and the body that names "shed", the class and the level. */
    private void assertShed(HttpResponse<String> response, String requestClass, String level) throws Exception {
        assertEquals(503, response.statusCode(), "expected a shed for " + requestClass + " at " + level
                + ", got " + response.statusCode() + " " + response.body());
        assertTrue(response.headers().firstValue("Retry-After").isPresent(), "a shed must send Retry-After");
        JsonNode body = json.readTree(response.body());
        assertEquals("shed", body.get("error").asText());
        assertEquals(requestClass, body.get("class").asText());
        assertEquals(level, body.get("level").asText());
    }
}
