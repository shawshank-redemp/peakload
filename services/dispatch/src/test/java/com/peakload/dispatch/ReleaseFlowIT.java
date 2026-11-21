package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Release flow: an order that is DELIVERED or CANCELLED frees its rider; a CANCELLED order still pending
 * is removed from pending_orders; a duplicate status event changes nothing. Invariants are checked at the end
 * of every test.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseFlowIT extends AbstractDispatchIT {

    private static final double PICKUP_LAT = 18.5204;
    private static final double PICKUP_LON = 73.8567;

    @Test
    void deliveredOrderFreesRiderAndTheNextPendingOrderGetsIt() throws Exception {
        String city = unique("city");
        String rider = registerRider("Asha", city);
        ping(rider, PICKUP_LAT, PICKUP_LON);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        produceOrderCreated(UUID.randomUUID(), first, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("first order assigned to the only rider", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND rider_id = ?",
                        first, UUID.fromString(rider)) == 1);
        produceOrderCreated(UUID.randomUUID(), second, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("second order waits: the rider is BUSY", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM pending_orders WHERE order_id = ?", second) == 1);

        double releasedBefore = counter("riders.released");
        produceOrderStatusChanged(UUID.randomUUID(), first, "DELIVERED");
        awaitTrue("first assignment completed", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'COMPLETED'", first) == 1);
        // Fails if the rider was not freed by the DELIVERED event, or was freed more than once.
        assertEquals("AVAILABLE", riderStatus(rider));
        assertEquals(releasedBefore + 1, counter("riders.released"));

        // The seen key is 30 s; refresh it so the retry job still sees the rider as fresh.
        ping(rider, PICKUP_LAT, PICKUP_LON);
        awaitTrue("pending order gets the freed rider on the next retry", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'ACTIVE'", second) == 1);
        assertEquals(0, count("SELECT count(*) FROM pending_orders WHERE order_id = ?", second));
        assertEquals("BUSY", riderStatus(rider));
        Invariants.assertHold(jdbc);
    }

    @Test
    void cancelledWhilePendingRemovesThePendingRow() throws Exception {
        String city = unique("empty-city"); // no riders here, so the order stays pending
        UUID order = UUID.randomUUID();
        double cancelledBefore = counter("pending.cancelled");

        produceOrderCreated(UUID.randomUUID(), order, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("order pending", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM pending_orders WHERE order_id = ?", order) == 1);

        produceOrderStatusChanged(UUID.randomUUID(), order, "CANCELLED");
        awaitTrue("pending row removed", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM pending_orders WHERE order_id = ?", order) == 0);
        // Fails if the cancellation did not count as a pending removal, or if an assignment was made.
        assertEquals(cancelledBefore + 1, counter("pending.cancelled"));
        assertEquals(0, count("SELECT count(*) FROM assignments WHERE order_id = ?", order));
        Invariants.assertHold(jdbc);
    }

    @Test
    void cancelledAfterAssignmentFreesTheRider() throws Exception {
        String city = unique("city");
        String rider = registerRider("Bala", city);
        ping(rider, PICKUP_LAT, PICKUP_LON);
        UUID order = UUID.randomUUID();
        double releasedBefore = counter("riders.released");

        produceOrderCreated(UUID.randomUUID(), order, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("order assigned", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'ACTIVE'", order) == 1);

        produceOrderStatusChanged(UUID.randomUUID(), order, "CANCELLED");
        awaitTrue("assignment completed and rider freed", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'COMPLETED'", order) == 1);
        assertEquals("AVAILABLE", riderStatus(rider));
        assertEquals(releasedBefore + 1, counter("riders.released"));
        Invariants.assertHold(jdbc);
    }

    @Test
    void duplicateStatusEventChangesNothing() throws Exception {
        String city = unique("city");
        String rider = registerRider("Chitra", city);
        ping(rider, PICKUP_LAT, PICKUP_LON);
        UUID order = UUID.randomUUID();
        UUID cancelEvent = UUID.randomUUID();

        produceOrderCreated(UUID.randomUUID(), order, city, PICKUP_LAT, PICKUP_LON);
        awaitTrue("order assigned", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'ACTIVE'", order) == 1);
        produceOrderStatusChanged(cancelEvent, order, "CANCELLED");
        awaitTrue("released", Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM assignments WHERE order_id = ? AND status = 'COMPLETED'", order) == 1);
        long versionAfterRelease = riderVersion(rider);

        // Same event id again: a redelivery. Must be recognised as a duplicate.
        double duplicatesBefore = counter("events.duplicates.ignored");
        produceOrderStatusChanged(cancelEvent, order, "CANCELLED");
        awaitTrue("duplicate counted", Duration.ofSeconds(30),
                () -> counter("events.duplicates.ignored") >= duplicatesBefore + 1);
        assertEquals(versionAfterRelease, riderVersion(rider), "a duplicate must not touch the rider");

        // A new event id for the same order (no ACTIVE assignment left): nothing to release, so nothing changes.
        double noActiveBefore = counter("release.no.active");
        produceOrderStatusChanged(UUID.randomUUID(), order, "DELIVERED");
        awaitTrue("release attempt with no active assignment counted", Duration.ofSeconds(30),
                () -> counter("release.no.active") >= noActiveBefore + 1);
        assertEquals(versionAfterRelease, riderVersion(rider), "no second release may happen");
        assertEquals("AVAILABLE", riderStatus(rider));
        assertEquals(1, count("SELECT count(*) FROM assignments WHERE order_id = ?", order));
        Invariants.assertHold(jdbc);
    }

    private long riderVersion(String riderId) {
        Long version = jdbc.queryForObject("SELECT version FROM riders WHERE id = ?", Long.class, UUID.fromString(riderId));
        return version == null ? -1 : version;
    }
}
