package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * Checks that an order and its outbox event commit together or not at all.
 *
 * <p>The outbox writer is a spy whose append() throws. That happens after the order row is inserted
 * and before the transaction commits, so the whole transaction must roll back. This class uses its
 * own Spring context because of the spy. The containers are still shared.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AtomicityIT extends AbstractIntegrationTest {

    @SpyBean
    private OutboxWriter outboxWriter;

    @Test
    void failureAfterOrderInsertRollsBackOrderIdempotencyKeyAndOutbox() throws Exception {
        String client = unique("client");
        String key = unique("key");
        long outboxRowsBefore = count("SELECT count(*) FROM outbox");

        doThrow(new IllegalStateException("injected failure before commit"))
                .when(outboxWriter).append(anyString(), any(UUID.class), anyString(), anyLong(), anyString());
        try {
            HttpResponse<String> response = postOrder(key, client, ORDER_BODY);
            assertTrue(response.statusCode() >= 500, "the injected failure must surface, got " + response.statusCode());
        } finally {
            reset(outboxWriter);
        }

        // Fails if the order row survived the rollback.
        assertEquals(0, ordersForClient(client), "no order row may exist after a rolled-back create");
        // Fails if the idempotency row survived: a retry would then replay a non-existent order.
        assertEquals(0, count("SELECT count(*) FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ?",
                client, key));
        // Fails if an outbox row was committed without its order.
        assertEquals(outboxRowsBefore, count("SELECT count(*) FROM outbox"), "no outbox row may be committed");

        // Control: with the injection removed, the same kind of request succeeds. This shows the
        // failure above came from the injection, not from a broken setup.
        HttpResponse<String> retry = postOrder(key, client, ORDER_BODY);
        assertEquals(201, retry.statusCode());
        assertEquals(1, ordersForClient(client));
    }
}
