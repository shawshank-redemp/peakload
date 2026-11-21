package com.peakload.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads orders and changes their status.
 *
 * <p>A status change runs in one transaction: read the order, check the version and the transition
 * rule, then run one conditional UPDATE. The UPDATE only matches if the version and status are still
 * the ones we read. If another request changed the order in between, the UPDATE matches zero rows
 * and the caller gets 409. There are no in-memory locks.
 */
@Service
public class OrderStatusService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final MeterRegistry registry;

    OrderStatusService(JdbcTemplate jdbc, OutboxWriter outbox, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.registry = registry;
    }

    /** @throws ApiException 404 if the order does not exist */
    @Transactional(readOnly = true)
    public OrderView find(UUID id) {
        return findView(id).orElseThrow(() -> orderNotFound(id));
    }

    /**
     * Moves an order to a new status.
     *
     * @param expectedVersion the version the client last read; must match the stored version
     * @throws ApiException 404 if the order is missing; 409 if the version no longer matches;
     *         422 if the move is not allowed from the current status
     */
    @Transactional
    public OrderView changeStatus(UUID id, long expectedVersion, OrderStatus target) {
        Snapshot current = readSnapshot(id).orElseThrow(() -> orderNotFound(id));

        // Version is checked before legality. A stale client that repeats an old move gets 409
        // (re-read first), not 422.
        if (current.version() != expectedVersion) {
            throw versionConflict(expectedVersion, current.version());
        }
        if (!current.status().canTransitionTo(target)) {
            throw ApiException.unprocessable(
                    "illegal_transition",
                    "cannot move an order from " + current.status() + " to " + target);
        }

        int updated = jdbc.update("""
                UPDATE orders
                SET status = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ? AND status = ?
                """, target.name(), id, expectedVersion, current.status().name());
        if (updated == 0) {
            // Someone else changed the order between our read and this UPDATE.
            throw ApiException.conflict(
                    "version_conflict", "the order was changed by another request; re-read it and retry");
        }

        // Read the row we just wrote (same transaction) so the event carries the new version and city.
        OrderView updatedView = findView(id).orElseThrow(() -> orderNotFound(id));
        // Same transaction as the UPDATE: the event exists if and only if the status change committed.
        outbox.append(OutboxWriter.ORDER_STATUS_CHANGED, id,
                updatedView.status(), updatedView.version(), updatedView.city());

        Counter.builder("orders.status.transitions")
                .description("Successful order status changes")
                .tag("from", current.status().name())
                .tag("to", target.name())
                .register(registry)
                .increment();

        return updatedView;
    }

    private Optional<Snapshot> readSnapshot(UUID id) {
        return jdbc.query("SELECT status, version FROM orders WHERE id = ?",
                        (rs, rowNum) -> new Snapshot(
                                OrderStatus.valueOf(rs.getString("status")),
                                rs.getLong("version")),
                        id)
                .stream()
                .findFirst();
    }

    private Optional<OrderView> findView(UUID id) {
        return jdbc.query("""
                SELECT id, payload->>'customerId' AS customer_id, payload->>'city' AS city,
                       status, (payload->>'totalPaise')::bigint AS total_paise, version,
                       created_at, updated_at
                FROM orders
                WHERE id = ?
                """,
                (rs, rowNum) -> new OrderView(
                        rs.getObject("id", UUID.class),
                        rs.getString("customer_id"),
                        rs.getString("city"),
                        rs.getString("status"),
                        rs.getLong("total_paise"),
                        rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                id)
                .stream()
                .findFirst();
    }

    private static ApiException orderNotFound(UUID id) {
        return ApiException.notFound("order_not_found", "order " + id + " does not exist");
    }

    private static ApiException versionConflict(long expected, long actual) {
        return ApiException.conflict(
                "version_conflict",
                "expected version " + expected + " but the order is at version " + actual + "; re-read it and retry");
    }

    private record Snapshot(OrderStatus status, long version) {}
}
