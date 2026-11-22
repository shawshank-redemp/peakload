package com.peakload.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
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

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendWait;
    private final Counter published;
    private final Counter failures;

    /**
     * @param sendWait how long the relay waits for one ack. This must be longer than the producer's
     *                 delivery.timeout.ms. Then a send is either acked or has definitely failed by the
     *                 time the relay gives up, so a late copy cannot land after the relay has retried.
     */
    OutboxRelay(
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate tx,
            @Value("${peakload.outbox.relay.batch-size:100}") int batchSize,
            @Value("${peakload.outbox.relay.send-wait:PT15S}") Duration sendWait,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.sendWait = sendWait;
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
                SELECT id, created_at, aggregate_id, event_type, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")),
                batchSize);

        for (OutboxRow row : rows) {
            try {
                // Key = order id, so all events for one order go to the same partition, in order.
                kafka.send(record(row)).get(sendWait.toMillis(), TimeUnit.MILLISECONDS);
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
            // created_at is part of the primary key, so the update names it and touches one partition only.
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ? AND created_at = ?",
                    row.id(), row.createdAt());
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

    private record OutboxRow(UUID id, OffsetDateTime createdAt, UUID aggregateId, String eventType, String payload) {}

    /** @param fullBatch true if the batch reached batch-size, so more rows may be waiting */
    private record BatchResult(boolean fullBatch, boolean failed) {}
}
