package com.peakload.dispatch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically retries pending orders, oldest first, and reports how many are waiting. */
@Component
class PendingOrderJob {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderJob.class);

    private final JdbcTemplate jdbc;
    private final AssignmentService assignments;
    private final int batchSize;

    PendingOrderJob(JdbcTemplate jdbc, AssignmentService assignments,
                    @Value("${peakload.dispatch.pending.batch-size:50}") int batchSize,
                    MeterRegistry registry) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.batchSize = batchSize;
        Gauge.builder("pending.orders", jdbc, j -> {
                    try {
                        Number count = j.queryForObject("SELECT count(*) FROM pending_orders", Number.class);
                        return count == null ? 0 : count.doubleValue();
                    } catch (DataAccessException e) {
                        return Double.NaN; // a scrape must not fail because the database is briefly down
                    }
                })
                .description("Orders waiting for a rider (pending_orders)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${peakload.dispatch.pending.retry-interval:PT5S}")
    void retryPending() {
        // Round-robin: orders never tried (last_attempt_at IS NULL) come first, then the least recently tried.
        // Ordering by age alone let orders that can never be served fill every batch, so newer orders starved.
        List<UUID> ids = jdbc.queryForList("""
                SELECT order_id FROM pending_orders
                ORDER BY last_attempt_at NULLS FIRST, created_at
                LIMIT ?
                """, UUID.class, batchSize);
        for (UUID id : ids) {
            try {
                assignments.retryPending(id);
            } catch (RuntimeException e) {
                // One failing order must not stop the others. It stays pending and is retried next run.
                log.error("retry of pending order {} failed; will retry: {}", id, e.toString());
            }
        }
    }
}
