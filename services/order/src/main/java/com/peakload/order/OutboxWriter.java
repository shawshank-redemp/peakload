package com.peakload.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes events to the outbox table. Call this inside the same transaction as the change it
 * describes, so an event exists if and only if the change was committed.
 */
@Component
class OutboxWriter {

    static final String AGGREGATE_TYPE = "order";
    static final String ORDER_CREATED = "OrderCreated";
    static final String ORDER_STATUS_CHANGED = "OrderStatusChanged";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts one event row. The row's id is the event id (UUIDv7) that consumers use to deduplicate. */
    void append(String eventType, UUID orderId, String status, long version, String city) {
        append(eventType, orderId, status, version, city, Map.of());
    }

    /** As above, with extra payload fields (for example the pickup point on OrderCreated). */
    void append(String eventType, UUID orderId, String status, long version, String city, Map<String, Object> extra) {
        UUID eventId = UuidCreator.getTimeOrderedEpoch();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("status", status);
        payload.put("version", version);
        payload.put("city", city);
        payload.put("occurredAt", Instant.now().toString());
        payload.putAll(extra);

        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, eventId, AGGREGATE_TYPE, orderId, eventType, toJson(payload));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
