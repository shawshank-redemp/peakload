package com.peakload.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes events to the dispatch outbox. Call it inside the transaction of the change it describes, so
 * an event exists if and only if that change was committed.
 */
@Component
class DispatchOutboxWriter {

    static final String AGGREGATE_TYPE = "assignment";
    static final String RIDER_ASSIGNED = "RiderAssigned";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    DispatchOutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts one event. Its id (UUIDv7) is the event id consumers use to deduplicate; aggregate is the order. */
    void append(String eventType, UUID orderId, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, UuidCreator.getTimeOrderedEpoch(), AGGREGATE_TYPE, orderId, eventType, toJson(payload));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
