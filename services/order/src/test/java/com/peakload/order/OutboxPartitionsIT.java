package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Partition maintenance on the outbox. The relay is switched off here (interval one hour) so that a row
 * this test writes is not published behind its back, and the maintenance schedule is pushed out for the same
 * reason. The tests call the job's methods directly.
 */
// Its own context (different properties). Dropped after the class, so its Kafka consumer does not stay in the
// shared consumer group and take messages from other test classes.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "peakload.outbox.relay.interval=PT1H",
        "peakload.outbox.partitions.initial-delay=PT1H"})
class OutboxPartitionsIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxPartitions partitions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void partitionWithAnUnpublishedRowIsNeverDropped() {
        YearMonth old = YearMonth.now(ZoneOffset.UTC).minusMonths(3); // entirely older than the 30-day retention
        String name = "outbox_p_" + old.getYear() + "_" + String.format("%02d", old.getMonthValue());
        partitions.ensurePartition(old);

        UUID eventId = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(old.atDay(10).atStartOfDay().toInstant(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'order', ?, 'OrderCreated', '{}'::jsonb, ?)
                """, eventId, UUID.randomUUID(), createdAt);

        partitions.purgeOld(Instant.now());
        assertTrue(partitionExists(name), "a partition with an unpublished row must not be dropped");
        assertEquals(1L, countRows("SELECT count(*) FROM outbox WHERE id = ?", eventId));

        // Once the row is published, the same partition is eligible and is dropped.
        jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ? AND created_at = ?", eventId, createdAt);
        partitions.purgeOld(Instant.now());
        assertFalse(partitionExists(name), "a fully published old partition should be dropped");
    }

    @Test
    void maintenanceCreatesTheNextMonthsAndIsRepeatable() {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        partitions.ensureUpcoming(now);
        partitions.ensureUpcoming(now); // second call must do nothing and not fail
        for (int i = 0; i <= 3; i++) {
            YearMonth month = now.plusMonths(i);
            String name = "outbox_p_" + month.getYear() + "_" + String.format("%02d", month.getMonthValue());
            assertTrue(partitionExists(name), "expected partition " + name);
        }
    }

    private boolean partitionExists(String name) {
        return countRows("SELECT count(*) FROM pg_class WHERE relname = ?", name) > 0;
    }

    private long countRows(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
