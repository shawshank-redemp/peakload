package com.peakload.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired idempotency keys. Each DELETE removes at most batch-size rows and commits on its
 * own, so no single statement holds locks for long. The job keeps going while batches are full.
 *
 * <p>Deleting an expired key does not affect correctness: an expired key already counts as unseen.
 */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private static final String DELETE_BATCH = """
            DELETE FROM idempotency_keys
            WHERE (client_id, idempotency_key) IN (
                SELECT client_id, idempotency_key
                FROM idempotency_keys
                WHERE expires_at <= now()
                LIMIT ?)
            """;

    private final JdbcTemplate jdbc;
    private final int batchSize;

    IdempotencyCleanupJob(JdbcTemplate jdbc, IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.batchSize = properties.cleanup().batchSize();
    }

    @Scheduled(fixedDelayString = "${peakload.idempotency.cleanup.interval:PT1M}")
    void deleteExpiredKeys() {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update(DELETE_BATCH, batchSize);
            total += deleted;
        } while (deleted == batchSize);

        if (total > 0) {
            log.info("deleted {} expired idempotency keys", total);
        }
    }
}
