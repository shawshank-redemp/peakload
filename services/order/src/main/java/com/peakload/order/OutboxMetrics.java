package com.peakload.order;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog gauges. Each value is read from the database when Prometheus scrapes.
 * The outbox has a partial index on unpublished rows, so both queries stay cheap.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        Gauge.builder("outbox.backlog", jdbc,
                        j -> scalar(j, "SELECT count(*) FROM outbox WHERE published_at IS NULL"))
                .description("Outbox rows not yet published")
                .register(registry);
        Gauge.builder("outbox.oldest.unpublished.age.seconds", jdbc,
                        j -> scalar(j, """
                                SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at)), 0)
                                FROM outbox WHERE published_at IS NULL
                                """))
                .description("Age in seconds of the oldest unpublished outbox row (0 when none)")
                .register(registry);
    }

    private static double scalar(JdbcTemplate jdbc, String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (DataAccessException e) {
            // A scrape must not fail because the database is briefly unavailable.
            return Double.NaN;
        }
    }
}
