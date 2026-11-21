package com.peakload.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unpublished outbox rows to the "order-events" topic.
 *
 * <p>Each batch runs in one transaction. Rows are selected with FOR UPDATE SKIP LOCKED, so two
 * relay instances never publish the same row at the same time. A row is marked published only
 * after the broker acknowledges it. If a send fails, the batch stops, the rows already acknowledged
 * are committed, and the failed row stays unpublished for the next run.
 *
 * <p>Delivery is at-least-once. A row can be published twice, for example if the service crashes
 * after the broker acknowledged a send but before the transaction committed. Consumers ignore
 * the duplicate by its event id.
 */
@Component
class OutboxRelay {

    static final String TOPIC = "order-events";
    static final String HEADER_EVENT_ID = "event-id";
    static final String HEADER_EVENT_TYPE = "event-type";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter published;
    private final Counter failures;

    OutboxRelay(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate tx,
            @Value("${peakload.outbox.relay.batch-size:100}") int batchSize,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.events.published")
                .description("Outbox events acknowledged by the broker")
                .register(registry);
        this.failures = Counter.builder("outbox.publish.failures")
                .description("Outbox publish attempts that failed; the row is retried")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${peakload.outbox.relay.interval:PT1S}")
    void relay() {
        // Keep taking batches while they are full, so a backlog drains without waiting for the next tick.
        // After a failed send, stop: do not hammer a broker that just failed; the next tick retries.
        boolean moreToDo;
        do {
            BatchResult result = tx.execute(status -> publishBatch());
            moreToDo = result != null && result.fullBatch() && !result.failed();
        } while (moreToDo);
    }

    private BatchResult publishBatch() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT id, aggregate_id, event_type, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                batchSize);

        for (OutboxRow row : rows) {
            try {
                // Key = order id, so all events for one order go to the same partition, in order.
                kafka.send(record(row)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.increment();
                return new BatchResult(rows.size() == batchSize, true);
            } catch (Exception e) {
                failures.increment();
                log.warn("publish failed for outbox event {} (order {}); will retry: {}",
                        row.id(), row.aggregateId(), e.toString());
                return new BatchResult(rows.size() == batchSize, true);
            }
            // Only reached after the broker acknowledged this row.
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
            published.increment();
        }
        return new BatchResult(rows.size() == batchSize, false);
    }

    private static ProducerRecord<String, String> record(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC, row.aggregateId().toString(), row.payload());
        record.headers().add(HEADER_EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload) {}

    /** @param fullBatch true if the batch reached batch-size, so more rows may be waiting */
    private record BatchResult(boolean fullBatch, boolean failed) {}
}
