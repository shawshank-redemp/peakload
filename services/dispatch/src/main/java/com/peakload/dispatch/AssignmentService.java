package com.peakload.dispatch;

import com.github.f4b6a3.uuid.UuidCreator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides which rider takes an order, and records that decision.
 *
 * <p><b>One transaction per event.</b> The processed_events insert, the candidate attempts and the
 * pending-order insert all run in one transaction. The event is marked processed if and only if its
 * outcome is committed. If the database fails halfway, nothing is marked processed, the event is
 * redelivered, and the work starts again from the beginning. Without the single transaction, a crash
 * after marking the event but before assigning would lose the order.
 *
 * <p><b>One savepoint per candidate.</b> Each candidate runs in a nested transaction (a savepoint), so
 * "rider BUSY + assignment row + RiderAssigned outbox row" commits or rolls back together for that
 * candidate. A unique violation on assignments.order_id means another attempt already assigned this order;
 * the savepoint rolls back and the event is treated as a successful no-op.
 *
 * <p>No in-memory locks. Correctness rests on the conditional UPDATE (status = AVAILABLE, row lock) and
 * on the unique constraints.
 */
@Service
public class AssignmentService {

    /** Outcome of handling one event. */
    public enum Outcome { DUPLICATE, ASSIGNED, ALREADY_ASSIGNED, PENDING }

    /** The constraint that makes one assignment per order. */
    static final String ORDER_UNIQUE_CONSTRAINT = "assignments_order_id_key";

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate nested;
    private final CandidateFinder candidates;
    private final DispatchOutboxWriter outbox;
    private final int maxAgeSeconds;
    private final Counter assignments;
    private final Counter attempts;
    private final Counter conflicts;
    private final Counter noCandidate;
    private final Counter duplicates;
    private final Counter gaveUp;
    private final Counter completed;
    private final Counter pendingCancelled;
    private final Counter ridersReleased;
    private final Counter releaseNoActive;
    private final Counter releaseInconsistent;
    private final Counter statusIgnored;

    AssignmentService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CandidateFinder candidates,
            DispatchOutboxWriter outbox,
            DispatchProperties properties,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.candidates = candidates;
        this.outbox = outbox;
        this.maxAgeSeconds = (int) properties.pending().maxAge().toSeconds();
        this.assignments = Counter.builder("assignments").description("Riders assigned to orders").register(registry);
        this.attempts = Counter.builder("assignment.attempts")
                .description("Candidate riders tried for an assignment").register(registry);
        this.conflicts = Counter.builder("candidate.conflicts")
                .description("Candidate attempts lost to another writer (the conditional UPDATE matched no row)")
                .register(registry);
        this.noCandidate = Counter.builder("no.candidate")
                .description("Orders with no fresh candidate, moved to pending_orders").register(registry);
        this.duplicates = Counter.builder("events.duplicates.ignored")
                .description("OrderCreated events already processed").register(registry);
        this.gaveUp = Counter.builder("pending.expired")
                .description("Pending orders given up after the maximum age").register(registry);
        this.completed = Counter.builder("assignments.completed")
                .description("Assignments completed by a DELIVERED or CANCELLED status event").register(registry);
        this.pendingCancelled = Counter.builder("pending.cancelled")
                .description("Pending orders removed because the order was cancelled").register(registry);
        this.ridersReleased = Counter.builder("riders.released")
                .description("Riders set back to AVAILABLE after their assignment ended").register(registry);
        this.releaseNoActive = Counter.builder("release.no.active")
                .description("DELIVERED or CANCELLED for an order with no ACTIVE assignment").register(registry);
        this.releaseInconsistent = Counter.builder("release.inconsistent")
                .description("Release skipped: the assignment was ACTIVE but its rider was not BUSY").register(registry);
        this.statusIgnored = Counter.builder("status.changes.ignored")
                .description("OrderStatusChanged events for statuses dispatch does not act on").register(registry);
    }

    /** Handles one OrderCreated event: dedupe, then assign or park the order in one transaction. */
    public Outcome onOrderCreated(UUID eventId, OrderCreatedEvent event) {
        return tx.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO processed_events (event_id, event_type, aggregate_id)
                    VALUES (?, 'OrderCreated', ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, event.orderId());
            if (inserted == 0) {
                duplicates.increment();
                return Outcome.DUPLICATE;
            }
            Attempt attempt = tryCandidates(event.orderId(), event.city(), event.pickupLat(), event.pickupLon());
            if (attempt.alreadyAssigned()) {
                return Outcome.ALREADY_ASSIGNED;
            }
            if (attempt.riderId() != null) {
                assignments.increment();
                return Outcome.ASSIGNED;
            }
            noCandidate.increment();
            jdbc.update("""
                    INSERT INTO pending_orders (order_id, city, pickup_lat, pickup_lon)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (order_id) DO NOTHING
                    """, event.orderId(), event.city(), event.pickupLat(), event.pickupLon());
            return Outcome.PENDING;
        });
    }

    /**
     * Handles one OrderStatusChanged event. Only DELIVERED and CANCELLED matter here.
     *
     * <p>Ordering inside the transaction matters. For CANCELLED, the pending row is deleted first. If the
     * pending retry job is assigning this order at the same moment, the DELETE waits for that job to commit.
     * The assignment is then read in a new statement, which sees the committed ACTIVE row and releases it.
     * In the other order, the release could miss an assignment that commits a moment later, and the rider
     * would stay BUSY for good.
     *
     * <p>The release (assignment COMPLETED and rider AVAILABLE) runs in a savepoint. If the rider is not
     * BUSY, the savepoint is rolled back, the event is still marked processed, and the inconsistency is logged
     * and counted. Nothing unsafe is done.
     */
    public void onOrderStatusChanged(UUID eventId, OrderStatusEvent event) {
        tx.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO processed_events (event_id, event_type, aggregate_id)
                    VALUES (?, 'OrderStatusChanged', ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, event.orderId());
            if (inserted == 0) {
                duplicates.increment();
                return;
            }
            switch (event.status()) {
                case "CANCELLED" -> {
                    if (jdbc.update("DELETE FROM pending_orders WHERE order_id = ?", event.orderId()) == 1) {
                        pendingCancelled.increment();
                    }
                    releaseAssignment(event.orderId());
                }
                case "DELIVERED" -> releaseAssignment(event.orderId());
                default -> {
                    statusIgnored.increment();
                    log.debug("ignoring status {} for order {}", event.status(), event.orderId());
                }
            }
        });
    }

    /** Completes the ACTIVE assignment of an order and frees its rider, if there is one. */
    private void releaseAssignment(UUID orderId) {
        try {
            Boolean released = nested.execute(status -> {
                List<UUID> riders = jdbc.queryForList("""
                        UPDATE assignments
                        SET status = 'COMPLETED'
                        WHERE order_id = ? AND status = 'ACTIVE'
                        RETURNING rider_id
                        """, UUID.class, orderId);
                if (riders.isEmpty()) {
                    return null; // nothing to release
                }
                int updated = jdbc.update("""
                        UPDATE riders
                        SET status = 'AVAILABLE', version = version + 1, updated_at = now()
                        WHERE id = ? AND status = 'BUSY'
                        """, riders.get(0));
                if (updated == 0) {
                    throw new CandidateRejected("rider " + riders.get(0) + " is not BUSY");
                }
                return Boolean.TRUE;
            });
            if (released == null) {
                releaseNoActive.increment();
                log.debug("no ACTIVE assignment for order {}; nothing to release", orderId);
                return;
            }
            completed.increment();
            ridersReleased.increment();
            log.info("order {} released its rider", orderId);
        } catch (CandidateRejected e) {
            releaseInconsistent.increment();
            log.error("release skipped for order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Retries one pending order. Locking the pending row with SKIP LOCKED means two retry runs never
     * work on the same order. Removal and assignment are one transaction.
     */
    public void retryPending(UUID orderId) {
        tx.executeWithoutResult(status -> {
            List<PendingOrder> rows = jdbc.query("""
                    SELECT order_id, city, pickup_lat, pickup_lon,
                           EXTRACT(EPOCH FROM now() - created_at) AS age_seconds
                    FROM pending_orders
                    WHERE order_id = ?
                    FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> new PendingOrder(
                    rs.getObject("order_id", UUID.class),
                    rs.getString("city"),
                    rs.getDouble("pickup_lat"),
                    rs.getDouble("pickup_lon"),
                    rs.getDouble("age_seconds")), orderId);
            if (rows.isEmpty()) {
                return; // gone, or locked by another retry run
            }
            PendingOrder pending = rows.get(0);

            Attempt attempt = tryCandidates(pending.orderId(), pending.city(), pending.pickupLat(), pending.pickupLon());
            if (attempt.riderId() != null) {
                assignments.increment();
            }
            if (attempt.riderId() != null || attempt.alreadyAssigned()) {
                jdbc.update("DELETE FROM pending_orders WHERE order_id = ?", orderId);
                return;
            }
            if (pending.ageSeconds() >= maxAgeSeconds) {
                jdbc.update("DELETE FROM pending_orders WHERE order_id = ?", orderId);
                gaveUp.increment();
                log.error("gave up on pending order {} after {} s with no available rider in {}",
                        orderId, (long) pending.ageSeconds(), pending.city());
                return;
            }
            jdbc.update("""
                    UPDATE pending_orders
                    SET attempts = attempts + 1, last_attempt_at = now()
                    WHERE order_id = ?
                    """, orderId);
        });
    }

    /** Tries fresh candidates nearest first. Stops at the first success or at an already-assigned order. */
    private Attempt tryCandidates(UUID orderId, String city, double lat, double lon) {
        for (UUID riderId : candidates.fresh(city, lat, lon)) {
            attempts.increment();
            try {
                if (assignIfAvailable(orderId, riderId)) {
                    return new Attempt(riderId, false);
                }
                conflicts.increment();
            } catch (OrderAlreadyAssigned e) {
                return new Attempt(null, true);
            } catch (CandidateRejected e) {
                conflicts.increment();
                log.error("rider {} is AVAILABLE but has an active assignment; skipping: {}", riderId, e.getMessage());
            }
        }
        return new Attempt(null, false);
    }

    /**
     * One candidate, in a savepoint. Returns true if this attempt assigned the rider. Returns false if the
     * conditional UPDATE matched no row (another writer got there first).
     */
    private boolean assignIfAvailable(UUID orderId, UUID riderId) {
        return nested.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE riders
                    SET status = 'BUSY', version = version + 1, updated_at = now()
                    WHERE id = ? AND status = 'AVAILABLE'
                    """, riderId);
            if (updated == 0) {
                return false;
            }
            try {
                jdbc.update("""
                        INSERT INTO assignments (id, order_id, rider_id, status)
                        VALUES (?, ?, ?, 'ACTIVE')
                        """, UuidCreator.getTimeOrderedEpoch(), orderId, riderId);
            } catch (DuplicateKeyException e) {
                // Throw (not return) so the savepoint is rolled back. A failed statement leaves the
                // transaction aborted until then.
                if (violates(e, ORDER_UNIQUE_CONSTRAINT)) {
                    throw new OrderAlreadyAssigned();
                }
                // The other unique rule: the rider already has an ACTIVE assignment, although the row says AVAILABLE.
                throw new CandidateRejected("rider " + riderId + " already has an active assignment");
            }
            outbox.append(DispatchOutboxWriter.RIDER_ASSIGNED, orderId, Map.of(
                    "orderId", orderId.toString(),
                    "riderId", riderId.toString(),
                    "assignedAt", Instant.now().toString()));
            return true;
        });
    }

    private static boolean violates(DuplicateKeyException e, String constraint) {
        Throwable cause = e.getMostSpecificCause();
        return cause != null && cause.getMessage() != null && cause.getMessage().contains(constraint);
    }

    private record Attempt(UUID riderId, boolean alreadyAssigned) {}

    private record PendingOrder(UUID orderId, String city, double pickupLat, double pickupLon, double ageSeconds) {}

    /** Thrown inside a savepoint: this order already has an assignment. Not an error. */
    private static final class OrderAlreadyAssigned extends RuntimeException {
        OrderAlreadyAssigned() {
            super(null, null, false, false);
        }
    }

    /** Thrown inside a savepoint: this candidate cannot take the order, so skip it. */
    private static final class CandidateRejected extends RuntimeException {
        CandidateRejected(String message) {
            super(message, null, false, false);
        }
    }
}
