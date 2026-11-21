package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/** Status-change rules and optimistic concurrency of PATCH /orders/{id}/status. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StatusIT extends AbstractIntegrationTest {

    @Test
    void illegalMoveIsRejectedWith422() throws Exception {
        UUID orderId = createOrder(unique("client"));

        // CREATED -> DELIVERED skips steps, so it is illegal even with the correct version.
        HttpResponse<String> response = patchStatus(orderId, 0L, "DELIVERED");

        assertEquals(422, response.statusCode());
        assertEquals("illegal_transition", parse(response).get("error").asText());
        // The order must still be CREATED at version 0.
        assertEquals("CREATED", parse(getOrder(orderId)).get("status").asText());
    }

    @Test
    void unknownOrderIsReturnedAs404() throws Exception {
        HttpResponse<String> response = patchStatus(UUID.randomUUID(), 0L, "CONFIRMED");

        assertEquals(404, response.statusCode());
        assertEquals("order_not_found", parse(response).get("error").asText());
    }

    @Test
    void missingIfMatchIsRejectedWith400() throws Exception {
        UUID orderId = createOrder(unique("client"));

        HttpResponse<String> response = patchStatus(orderId, null, "CONFIRMED");

        assertEquals(400, response.statusCode());
        assertEquals("missing_if_match", parse(response).get("error").asText());
    }

    @Test
    void twentyConcurrentChangesWithSameVersionLetExactlyOneWin() throws Exception {
        String client = unique("client");
        UUID orderId = createOrder(client);

        List<HttpResponse<String>> responses =
                concurrently(20, () -> patchStatus(orderId, 0L, "CONFIRMED"));

        Map<Integer, Long> byStatus = responses.stream()
                .collect(Collectors.groupingBy(HttpResponse::statusCode, Collectors.counting()));
        // Fails if two writers both succeeded from the same version (lost update).
        assertEquals(1L, byStatus.getOrDefault(200, 0L), "exactly one 200, got " + byStatus);
        assertEquals(19L, byStatus.getOrDefault(409, 0L), "the rest must be 409, got " + byStatus);

        HttpResponse<String> current = getOrder(orderId);
        assertEquals(1, parse(current).get("version").asLong());
        assertEquals("CONFIRMED", parse(current).get("status").asText());
        // Only the winning change may write an event.
        assertEquals(1, outboxEventsFor(orderId, OutboxWriter.ORDER_STATUS_CHANGED));
    }
}
