package com.peakload.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the dispatch outbox to "dispatch-events". Same pattern and same timeout rules as the order
 * relay (see OutboxRelay in the order service):
 *
 * <ul>
 *   <li>Rows are selected with FOR UPDATE SKIP LOCKED, so two relay instances never publish one row at once.</li>
 *   <li>A row is marked published only after the broker acknowledges it.</li>
 *   <li>Each wait is bounded by send-wait, which must exceed the producer's delivery.timeout.ms.</li>
 *   <li>A failed send stops the batch; the rows already acknowledged are committed, and the failed row is retried.</li>
 * </ul>
 *
 * <p>Delivery is at-least-once. A duplicate on the topic is ignored by consumers through the event id.
 */
@Component
class DispatchOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendWait;
    private final Counter published;
    private final Counter failures;

    DispatchOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                        PlatformTransactionManager transactionManager,
                        DispatchProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(transactionManager);
        this.batchSize = properties.relay().batchSize();
        this.sendWait = properties.relay().sendWait();
        this.published = Counter.builder("dispatch.outbox.published")
                .description("Dispatch outbox events acknowledged by the broker").register(registry);
        this.failures = Counter.builder("dispatch.outbox.publish.failures")
                .description("Dispatch outbox publish attempts that failed; the row is retried").register(registry);
    }

    @Scheduled(fixedDelayString = "${peakload.dispatch.relay.interval:PT1S}")
    void relay() {
        // After a failed send, stop until the next tick instead of retrying a broker that just failed.
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
                kafka.send(record(row)).get(sendWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.increment();
                return new BatchResult(rows.size() == batchSize, true);
            } catch (Exception e) {
                failures.increment();
                log.warn("publish failed for dispatch event {} (order {}); will retry: {}",
                        row.id(), row.aggregateId(), e.toString());
                return new BatchResult(rows.size() == batchSize, true);
            }
            // Reached only after the broker acknowledged this row.
            jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
            published.increment();
        }
        return new BatchResult(rows.size() == batchSize, false);
    }

    private static ProducerRecord<String, String> record(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                EventNames.DISPATCH_EVENTS, row.aggregateId().toString(), row.payload());
        record.headers().add(EventNames.EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventNames.EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload) {}

    /** @param fullBatch the batch reached batch-size, so more rows may be waiting */
    private record BatchResult(boolean fullBatch, boolean failed) {}
}
