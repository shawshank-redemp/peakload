package com.peakload.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates orders with idempotency. Every step runs in one database transaction.
 *
 * <p>Concurrency comes from the database, not from in-memory locks. The primary key on
 * (client_id, idempotency_key) lets only one concurrent INSERT succeed. The others wait for that
 * transaction to finish, then either see its committed row or insert their own row if it rolled
 * back. A row found with SELECT ... FOR UPDATE is locked until this transaction ends.
 */
@Service
public class OrderService {

    /** Retries when the key row disappears between the INSERT and the SELECT (the cleanup job removed it). */
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final RequestHasher hasher;
    private final ObjectMapper mapper;
    private final OutboxWriter outbox;
    private final long ttlSeconds;
    private final Counter ordersCreated;
    private final Counter idempotentReplays;

    OrderService(
            JdbcTemplate jdbc,
            RequestHasher hasher,
            ObjectMapper mapper,
            OutboxWriter outbox,
            IdempotencyProperties properties,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.mapper = mapper;
        this.outbox = outbox;
        this.ttlSeconds = properties.ttl().toSeconds();
        // Micrometer's Prometheus naming drops a trailing "_created" from counter names, so the
        // name "orders.created" exports as orders_total. This name exports as orders_created_count_total.
        this.ordersCreated = Counter.builder("orders.created.count")
                .description("Orders created by POST /orders (first request for an idempotency key)")
                .register(registry);
        this.idempotentReplays = Counter.builder("idempotent.replays")
                .description("Requests answered from a stored idempotency response")
                .register(registry);
    }

    /**
     * Creates an order, or returns the stored answer if this key was already used.
     *
     * @return status 201 for a new order; status 200 with replay=true for a repeated request
     * @throws ApiException 400 for an invalid body; 422 if the key was used with a different body
     */
    @Transactional
    public IdempotentResult createOrder(String clientId, String idempotencyKey, CreateOrderRequest body) {
        OrderInput input = OrderInput.validated(body);
        String hash = hasher.hash(input);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (insertKey(clientId, idempotencyKey, hash) == 1) {
                return placeOrder(clientId, idempotencyKey, input);
            }

            Optional<KeyRow> existing = lockKey(clientId, idempotencyKey);
            if (existing.isEmpty()) {
                continue; // the expired row was deleted between the two statements; try again
            }

            KeyRow row = existing.get();
            if (row.expired()) {
                // An expired key counts as unseen. Reuse the row inside this transaction.
                resetKey(clientId, idempotencyKey, hash);
                return placeOrder(clientId, idempotencyKey, input);
            }
            if (!row.requestHash().equals(hash)) {
                throw ApiException.unprocessable(
                        "idempotency_key_reused",
                        "Idempotency-Key was already used with a different request body");
            }
            // Only committed rows are visible here, and each one has a stored response.
            // A replay is answered with 200 even though the original answer was 201.
            idempotentReplays.increment();
            return new IdempotentResult(200, row.responseBody(), true);
        }
        throw new IllegalStateException("idempotency key not resolved after " + MAX_ATTEMPTS + " attempts");
    }

    /** Returns 1 if this request owns the key, 0 if a row already exists. */
    private int insertKey(String clientId, String idempotencyKey, String hash) {
        return jdbc.update("""
                INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, expires_at)
                VALUES (?, ?, ?, now() + make_interval(secs => ?))
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """, clientId, idempotencyKey, hash, ttlSeconds);
    }

    private Optional<KeyRow> lockKey(String clientId, String idempotencyKey) {
        List<KeyRow> rows = jdbc.query("""
                SELECT request_hash, response_body::text AS response_body,
                       expires_at <= now() AS expired
                FROM idempotency_keys
                WHERE client_id = ? AND idempotency_key = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new KeyRow(
                        rs.getString("request_hash"),
                        rs.getString("response_body"),
                        rs.getBoolean("expired")),
                clientId, idempotencyKey);
        return rows.stream().findFirst();
    }

    private void resetKey(String clientId, String idempotencyKey, String hash) {
        jdbc.update("""
                UPDATE idempotency_keys
                SET request_hash = ?, order_id = NULL, response_status = NULL, response_body = NULL,
                    created_at = now(), expires_at = now() + make_interval(secs => ?)
                WHERE client_id = ? AND idempotency_key = ?
                """, hash, ttlSeconds, clientId, idempotencyKey);
    }

    private IdempotentResult placeOrder(String clientId, String idempotencyKey, OrderInput input) {
        UUID orderId = UuidCreator.getTimeOrderedEpoch(); // UUIDv7: time-ordered, so new rows append to the index
        jdbc.update("""
                INSERT INTO orders (id, client_id, status, version, payload, pickup_lat, pickup_lon)
                VALUES (?, ?, 'CREATED', 0, ?::jsonb, ?, ?)
                """, orderId, clientId, hasher.canonicalJson(input), input.pickupLat(), input.pickupLon());
        // Same transaction as the order row: the event exists if and only if the order was created.
        // The pickup point is in the event so dispatch can find riders without calling the order service.
        outbox.append(OutboxWriter.ORDER_CREATED, orderId, "CREATED", 0, input.city(),
                Map.of("pickupLat", input.pickupLat(), "pickupLon", input.pickupLon()));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", orderId.toString());
        view.put("status", "CREATED");
        view.put("customerId", input.customerId());
        view.put("city", input.city());
        view.put("totalPaise", input.totalPaise());

        // RETURNING gives back the JSON as Postgres stores it (jsonb), so a replay returns the same bytes.
        String storedBody = jdbc.queryForObject("""
                UPDATE idempotency_keys
                SET order_id = ?, response_status = 201, response_body = ?::jsonb
                WHERE client_id = ? AND idempotency_key = ?
                RETURNING response_body::text
                """, String.class, orderId, toJson(view), clientId, idempotencyKey);

        ordersCreated.increment();
        return new IdempotentResult(201, storedBody, false);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The answer to send: status, JSON body, and whether it came from a stored response. */
    public record IdempotentResult(int status, String body, boolean replay) {}

    /** A row in idempotency_keys. expired is decided by the database clock. */
    private record KeyRow(String requestHash, String responseBody, boolean expired) {}
}
