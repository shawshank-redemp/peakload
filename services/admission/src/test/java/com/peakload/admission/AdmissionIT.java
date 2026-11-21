package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Admission in front of a stub order service: header and status pass-through, replay, timeouts, routing, and
 * the local menu. The stub is in-process; Postgres is a container.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdmissionIT extends AbstractRedisAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
        // Short read timeout so the timeout test is quick. The default is 2 s.
        registry.add("peakload.admission.upstream.read-timeout", () -> "PT0.5S");
    }

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void resetStub() {
        STUB.clearReceived();
    }

    @Test
    void requiredHeadersGoUpstreamAndUpstreamStatusAndBodyComeBack() throws Exception {
        String order = "{\"orderId\":\"o-1\",\"status\":\"CREATED\"}";
        STUB.reply("POST", "/orders", new StubOrderService.Reply(201, Map.of("Location", "/orders/o-1"), order, 0));

        HttpResponse<String> response = send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "key-1")
                .header("X-Client-Id", "client-1")
                .header("X-Not-Forwarded", "secret"));

        assertEquals(201, response.statusCode());
        assertEquals(order, response.body(), "body is passed through unchanged");
        assertEquals("/orders/o-1", response.headers().firstValue("Location").orElse(null));

        // Fails if a required header did not reach the order service, or an unlisted one did.
        StubOrderService.Received seen = STUB.received().get(0);
        assertEquals("key-1", seen.headers().get("Idempotency-key"));
        assertEquals("client-1", seen.headers().get("X-client-id"));
        assertNotNull(seen.headers().get("Content-type"));
        assertNull(seen.headers().get("X-not-forwarded"), "unlisted headers must not be forwarded");
    }

    @Test
    void upstreamClientErrorsPassThroughWithStatusBodyAndIfMatch() throws Exception {
        String conflict = "{\"error\":\"version_conflict\"}";
        STUB.reply("PATCH", "/orders/o-2/status", StubOrderService.Reply.of(409, conflict));

        HttpResponse<String> response = send(request("PATCH", "/orders/o-2/status", "{\"status\":\"CONFIRMED\"}")
                .header("Content-Type", "application/json")
                .header("If-Match", "3"));

        // Fails if the upstream's 409 was turned into another status, or its body was changed.
        assertEquals(409, response.statusCode());
        assertEquals(conflict, response.body());
        assertEquals("3", STUB.received().get(0).headers().get("If-match"), "If-Match must reach the order service");
    }

    @Test
    void replayThroughAdmissionReturnsTheSameStoredBody() throws Exception {
        String stored = "{\"orderId\":\"o-9\",\"status\":\"CREATED\"}";
        STUB.reply("POST", "/orders", new StubOrderService.Reply(201, Map.of(), stored, 0));
        double successBefore = metrics.counter("admission.requests", "class", "CRITICAL", "outcome", "success").count();

        HttpResponse<String> first = send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Idempotency-Key", "replay-key")
                .header("X-Client-Id", "client-r"));
        // The order service answers a repeat with the stored response and Idempotent-Replay: true.
        STUB.reply("POST", "/orders", new StubOrderService.Reply(200, Map.of("Idempotent-Replay", "true"), stored, 0));
        HttpResponse<String> replay = send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Idempotency-Key", "replay-key")
                .header("X-Client-Id", "client-r"));

        assertEquals(201, first.statusCode());
        assertEquals(200, replay.statusCode());
        // Fails if admission changed the body, or dropped the replay marker the client relies on.
        assertEquals(first.body(), replay.body(), "replay must return the identical body");
        assertEquals("true", replay.headers().firstValue("Idempotent-Replay").orElse(null));
        // Both calls carried the same key to the order service.
        assertEquals("replay-key", STUB.received().get(0).headers().get("Idempotency-key"));
        assertEquals("replay-key", STUB.received().get(1).headers().get("Idempotency-key"));
        assertTrue(metrics.counter("admission.requests", "class", "CRITICAL", "outcome", "success").count()
                >= successBefore + 2, "both requests must be counted as CRITICAL successes");
    }

    @Test
    void slowUpstreamBecomes504() throws Exception {
        STUB.reply("GET", "/orders/slow", new StubOrderService.Reply(200, Map.of(), "{}", 2_000));

        HttpResponse<String> response = send(request("GET", "/orders/slow", null));

        // Fails if a slow upstream is not reported as a gateway timeout with a clear body.
        assertEquals(504, response.statusCode());
        assertTrue(response.body().contains("read_timeout"), "body must name the error: " + response.body());
    }

    @Test
    void unknownRouteIs404() throws Exception {
        HttpResponse<String> response = send(request("GET", "/nope", null));

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("route_not_found"));
        assertEquals(0, STUB.received().size(), "an unknown route must not reach the order service");
    }

    @Test
    void browseReadsTheSeededMenuLocally() throws Exception {
        HttpResponse<String> response = send(request("GET", "/browse/restaurants/" + SPICE_ROUTE + "/menu", null));

        assertEquals(200, response.statusCode());
        // Fails if the seeded menu is not read from Postgres: the name, item count and availability come from the seed.
        assertTrue(response.body().contains("\"name\":\"Spice Route\""), response.body());
        assertTrue(response.body().contains("Paneer Tikka"));
        assertTrue(response.body().contains("Garlic Naan"));
        assertTrue(response.body().contains("\"available\":false"), "the seeded unavailable item must be reported");
        assertEquals(0, STUB.received().size(), "browse is served locally and must not call the order service");
    }

    @Test
    void browseForUnknownRestaurantIs404() throws Exception {
        HttpResponse<String> response = send(request("GET",
                "/browse/restaurants/00000000-0000-4000-8000-000000000000/menu", null));

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("restaurant_not_found"));
    }
}
