package com.peakload.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes "order-events" and handles each event once, even if the relay delivers it twice.
 *
 * <p>Consumer group "order-events-consumer": Kafka stores this group's read position on the topic.
 * Another consumer group reading the same topic would get its own full copy of every event.
 *
 * <p>Deduplication: the event id (the outbox id, sent in the "event-id" header) goes into
 * processed_events with ON CONFLICT DO NOTHING. Only the insert that adds a row triggers processing.
 * The insert and the processing run in one transaction. Processing is only a log line for now.
 *
 * <p>Delivery is at-least-once: a message can arrive again, and the duplicate is ignored here.
 */
@Component
class OrderEventConsumer {

    static final String CONSUMER_GROUP = "order-events-consumer";

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Counter consumed;
    private final Counter duplicates;

    OrderEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consumed = Counter.builder("events.consumed")
                .description("Messages received from order-events")
                .register(registry);
        this.duplicates = Counter.builder("events.duplicates.ignored")
                .description("Messages whose event id was already processed")
                .register(registry);
    }

    @KafkaListener(topics = OutboxRelay.TOPIC, groupId = CONSUMER_GROUP)
    void onEvent(ConsumerRecord<String, String> record) {
        consumed.increment();
        UUID eventId = requireEventId(record);
        String eventType = headerText(record, OutboxRelay.HEADER_EVENT_TYPE);
        UUID orderId = UUID.fromString(record.key());

        Boolean firstTime = tx.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO processed_events (event_id, event_type, aggregate_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, eventType, orderId);
            if (inserted == 1) {
                log.info("processing event {} type {} order {}", eventId, eventType, orderId);
            }
            return inserted == 1;
        });

        if (Boolean.TRUE.equals(firstTime)) {
            return;
        }
        duplicates.increment();
        log.info("duplicate event {} ignored", eventId);
    }

    private static UUID requireEventId(ConsumerRecord<String, String> record) {
        String text = headerText(record, OutboxRelay.HEADER_EVENT_ID);
        if (text == null) {
            // Without an event id we cannot deduplicate, so fail loudly instead of guessing.
            throw new IllegalArgumentException("message without " + OutboxRelay.HEADER_EVENT_ID + " header");
        }
        return UUID.fromString(text);
    }

    private static String headerText(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
