package com.peakload.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the monthly partitions of the outbox in step with time.
 *
 * <ul>
 *   <li>Creates the partitions for the current month and the next {@code months-ahead} months, so new rows always
 *       have a monthly partition and do not land in the DEFAULT partition.</li>
 *   <li>Detaches and drops a monthly partition only when the whole month is older than the retention period AND the
 *       partition holds no unpublished row.</li>
 * </ul>
 *
 * <p><b>Why dropping is safe.</b> Rows are inserted with created_at = now() (the start of their transaction), and
 * published_at only goes from NULL to a time, never back. A partition that is older than the retention period
 * receives no new rows, and once it has no unpublished row it never will again. The check and the drop run in
 * one transaction. The one case that breaks this is a transaction that started before the retention cut-off and
 * commits after it; retention is in days and transactions are short, so this is not expected to happen.
 */
@Component
class OutboxPartitions {

    private static final Logger log = LoggerFactory.getLogger(OutboxPartitions.class);
    static final Pattern NAME = Pattern.compile("outbox_p_(\\d{4})_(\\d{2})");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int monthsAhead;
    private final Duration retention;

    OutboxPartitions(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            @Value("${peakload.outbox.partitions.months-ahead:3}") int monthsAhead,
            @Value("${peakload.outbox.partitions.retention:P30D}") Duration retention) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.monthsAhead = monthsAhead;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${peakload.outbox.partitions.initial-delay:PT1M}",
            fixedDelayString = "${peakload.outbox.partitions.interval:PT1H}")
    void maintain() {
        try {
            ensureUpcoming(YearMonth.now(ZoneOffset.UTC));
            purgeOld(Instant.now());
        } catch (DataAccessException e) {
            // Maintenance is retried on the next tick. The relay and the order path do not depend on it.
            log.warn("outbox partition maintenance failed; retrying later: {}", e.getMessage());
        }
    }

    /** Creates the partitions for {@code from} and the following {@code months-ahead} months. Safe to repeat. */
    void ensureUpcoming(YearMonth from) {
        for (int i = 0; i <= monthsAhead; i++) {
            ensurePartition(from.plusMonths(i));
        }
    }

    /** Creates the monthly partition for {@code month} if it does not exist. */
    void ensurePartition(YearMonth month) {
        String name = "outbox_p_" + month.getYear() + "_" + String.format("%02d", month.getMonthValue());
        String from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        String to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + name
                    + " PARTITION OF outbox FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
        } catch (DataAccessException e) {
            // Usually this means the DEFAULT partition already holds rows for this month. It is logged, not hidden.
            log.warn("could not create outbox partition {}: {}", name, e.getMostSpecificCause().getMessage());
        }
    }

    /** Drops every monthly partition that is entirely older than the retention and has no unpublished row. */
    void purgeOld(Instant now) {
        Instant cutoff = now.minus(retention);
        List<String> names = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'outbox'::regclass
                ORDER BY c.relname
                """, String.class);
        for (String name : names) {
            Matcher m = NAME.matcher(name);
            if (!m.matches()) {
                continue; // the DEFAULT partition and anything else not created by this class
            }
            YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (monthEnd.isAfter(cutoff)) {
                continue; // the month is not yet entirely older than the retention
            }
            tx.executeWithoutResult(status -> dropIfFullyPublished(name));
        }
    }

    private void dropIfFullyPublished(String name) {
        Long unpublished = jdbc.queryForObject(
                "SELECT count(*) FROM " + name + " WHERE published_at IS NULL", Long.class);
        if (unpublished != null && unpublished > 0) {
            log.info("keeping outbox partition {}: {} unpublished rows", name, unpublished);
            return;
        }
        jdbc.execute("ALTER TABLE outbox DETACH PARTITION " + name);
        jdbc.execute("DROP TABLE " + name);
        log.info("dropped outbox partition {} (older than retention, fully published)", name);
    }
}
