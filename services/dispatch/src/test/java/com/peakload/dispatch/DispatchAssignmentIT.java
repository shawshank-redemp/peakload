package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/**
 * An OrderCreated event becomes one assignment, one RiderAssigned outbox row, and one published event.
 * Duplicates create nothing new, and stale riders are skipped. Each test uses its own city.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DispatchAssignmentIT extends AbstractDispatchIT {

    private static final double PICKUP_LAT = 18.5204;
    private static final double PICKUP_LON = 73.8567;

    @Test
    void orderCreatedBecomesOneAssignmentAndOneRiderAssignedEvent() throws Exception {
        String city = unique("city");
        String riderId = registerRider("Asha", city);
        ping(riderId, PICKUP_LAT, PICKUP_LON);
        UUID orderId = UUID.randomUUID();

        produceOrderCreated(UUID.randomUUID(), orderId, city, PICKUP_LAT, PICKUP_LON);

        awaitTrue("assignment for the order", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ?", orderId) == 1);
        // Fails if the assignment went to someone else or is not ACTIVE.
        assertEquals(1, count("SELECT count(*) FROM assignments WHERE order_id = ? AND rider_id = ? AND status = 'ACTIVE'",
                orderId, UUID.fromString(riderId)));
        // Fails if the rider was not marked BUSY in the same change.
        assertEquals("BUSY", riderStatus(riderId));
        // Fails if the RiderAssigned event was not written, or written twice.
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'RiderAssigned'", orderId));
        // Fails if the relay never published the event (the row gets published_at).
        awaitTrue("RiderAssigned published", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'RiderAssigned' "
                        + "AND published_at IS NOT NULL", orderId) == 1);
    }

    @Test
    void duplicateEventCreatesNothingNew() throws Exception {
        String city = unique("city");
        String riderId = registerRider("Bala", city);
        ping(riderId, PICKUP_LAT, PICKUP_LON);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        produceOrderCreated(eventId, orderId, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("first event assigned", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ?", orderId) == 1);

        double duplicatesBefore = counter("events.duplicates.ignored");
        double attemptsBefore = counter("assignment.attempts");
        produceOrderCreated(eventId, orderId, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("duplicate counted", Duration.ofSeconds(30),
                () -> counter("events.duplicates.ignored") >= duplicatesBefore + 1);

        // Fails if the duplicate was processed again: that would try a candidate (attempts would rise).
        assertEquals(attemptsBefore, counter("assignment.attempts"), "a duplicate must not attempt an assignment");
        assertEquals(1, count("SELECT count(*) FROM assignments WHERE order_id = ?", orderId));
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'RiderAssigned'", orderId));
        assertEquals(1, count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId));
    }

    @Test
    void staleCandidateIsSkippedAndTheFreshRiderIsAssigned() throws Exception {
        String city = unique("city");
        String staleRider = registerRider("Chitra", city);
        ping(staleRider, PICKUP_LAT, PICKUP_LON);            // nearest, but its last-seen key is removed below
        redis.delete("riders:seen:" + staleRider);
        String freshRider = registerRider("Dev", city);
        ping(freshRider, 18.5250, 73.8600);                  // about 0.6 km away, fresh
        UUID orderId = UUID.randomUUID();

        produceOrderCreated(UUID.randomUUID(), orderId, city, PICKUP_LAT, PICKUP_LON);

        awaitTrue("assignment for the order", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ?", orderId) == 1);
        // Fails if the stale rider (nearest) was assigned.
        assertEquals(0, count("SELECT count(*) FROM assignments WHERE rider_id = ?", UUID.fromString(staleRider)));
        assertEquals("AVAILABLE", riderStatus(staleRider));
        assertEquals(1, count("SELECT count(*) FROM assignments WHERE order_id = ? AND rider_id = ?",
                orderId, UUID.fromString(freshRider)));
    }

    @Test
    void orderWithNoFreshRiderGoesToPendingOrders() throws Exception {
        String city = unique("empty-city");
        UUID orderId = UUID.randomUUID();
        double noCandidateBefore = counter("no.candidate");

        produceOrderCreated(UUID.randomUUID(), orderId, city, PICKUP_LAT, PICKUP_LON);

        awaitTrue("order parked in pending_orders", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM pending_orders WHERE order_id = ?", orderId) == 1);
        assertEquals(0, count("SELECT count(*) FROM assignments WHERE order_id = ?", orderId));
        assertTrue(counter("no.candidate") >= noCandidateBefore + 1, "no.candidate must count the parked order");
    }
}
