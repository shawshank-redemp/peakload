package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/** Idempotency-Key behaviour of POST /orders against real Postgres. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyIT extends AbstractIntegrationTest {

    @Test
    void sameKeyAndBodyReturnsStoredOrderAndWritesNothingNew() throws Exception {
        String client = unique("client");
        String key = unique("key");

        HttpResponse<String> first = postOrder(key, client, ORDER_BODY);
        assertEquals(201, first.statusCode());
        UUID orderId = createdOrderId(first);

        HttpResponse<String> replay = postOrder(key, client, ORDER_BODY);
        assertEquals(200, replay.statusCode());
        assertEquals("true", replay.headers().firstValue("Idempotent-Replay").orElse(null));
        assertEquals(orderId, createdOrderId(replay));

        // Fails if the replay created a second order or a second OrderCreated event.
        assertEquals(1, ordersForClient(client));
        assertEquals(1, outboxEventsFor(orderId, OutboxWriter.ORDER_CREATED));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedWith422() throws Exception {
        String client = unique("client");
        String key = unique("key");

        assertEquals(201, postOrder(key, client, ORDER_BODY).statusCode());

        String differentBody = "{\"customerId\":\"c-it\",\"city\":\"Pune\",\"totalPaise\":9999}";
        HttpResponse<String> conflicting = postOrder(key, client, differentBody);
        assertEquals(422, conflicting.statusCode());
        assertEquals("idempotency_key_reused", parse(conflicting).get("error").asText());

        // The rejected request must not have created an order.
        assertEquals(1, ordersForClient(client));
    }

    @Test
    void fiftyConcurrentRequestsWithOneKeyCreateExactlyOneOrder() throws Exception {
        String client = unique("client");
        String key = unique("key");

        List<HttpResponse<String>> responses = concurrently(50, () -> postOrder(key, client, ORDER_BODY));

        Map<Integer, Long> byStatus = responses.stream()
                .collect(Collectors.groupingBy(HttpResponse::statusCode, Collectors.counting()));
        // Fails if two requests both won the insert (two 201s) or if any request failed.
        assertEquals(1L, byStatus.getOrDefault(201, 0L), "exactly one 201, got " + byStatus);
        assertEquals(49L, byStatus.getOrDefault(200, 0L), "the rest must be 200, got " + byStatus);

        UUID orderId = responses.stream()
                .filter(r -> r.statusCode() == 201)
                .findFirst()
                .map(r -> {
                    try {
                        return createdOrderId(r);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow();
        for (HttpResponse<String> response : responses) {
            assertEquals(orderId, createdOrderId(response), "every response must carry the same order id");
        }

        assertEquals(1, ordersForClient(client));
        assertEquals(1, outboxEventsFor(orderId, OutboxWriter.ORDER_CREATED));
        assertNotNull(orderId);
    }
}
