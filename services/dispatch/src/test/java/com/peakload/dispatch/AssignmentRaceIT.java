package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Contention tests: many orders against few riders. After each test the database-wide invariants (see
 * {@link Invariants}) must hold.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssignmentRaceIT extends AbstractDispatchIT {

    private static final double PICKUP_LAT = 18.5204;
    private static final double PICKUP_LON = 73.8567;

    @Autowired
    private AssignmentService assignments;

    /** 50 events published together through Kafka, 5 riders. Exactly 5 win; 45 wait. */
    @Test
    void fiftyOrdersAtOnceWithFiveRidersAssignFiveAndParkFortyFive() throws Exception {
        String city = unique("city");
        for (int i = 0; i < 5; i++) {
            String id = registerRider("R" + i, city);
            ping(id, PICKUP_LAT + i * 0.0005, PICKUP_LON);
        }

        List<UUID> orders = new ArrayList<>();
        List<ProducerRecord<String, String>> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID orderId = UUID.randomUUID();
            orders.add(orderId);
            batch.add(orderCreatedRecord(UUID.randomUUID(), orderId, city));
        }
        produceTogether(batch);

        String in = inClause(orders);
        awaitTrue("all 50 orders assigned or parked", Duration.ofSeconds(90), () ->
                count("SELECT count(*) FROM assignments WHERE order_id IN (" + in + ")", orders.toArray())
                        + count("SELECT count(*) FROM pending_orders WHERE order_id IN (" + in + ")", orders.toArray())
                        == 50);

        // Fails if more or fewer than five riders were assigned, or if an order was lost or counted twice.
        assertEquals(5, count("SELECT count(*) FROM assignments WHERE order_id IN (" + in + ")", orders.toArray()),
                "exactly five assignments");
        assertEquals(45, count("SELECT count(*) FROM pending_orders WHERE order_id IN (" + in + ")", orders.toArray()),
                "the other 45 orders wait in pending_orders");
        assertEquals(5, count("SELECT count(*) FROM riders WHERE city = ? AND status = 'BUSY'", city),
                "all five riders are BUSY");
        Invariants.assertHold(jdbc);
    }

    /**
     * 200 threads each handle one OrderCreated event through the real assignment service, against 20 riders.
     * Exactly 20 must come back ASSIGNED. The others must be PENDING, not lost, not duplicated.
     */
    @Test
    void twoHundredThreadsForTwentyRidersAssignExactlyTwentyAndKeepInvariants() throws Exception {
        String city = unique("city");
        for (int i = 0; i < 20; i++) {
            String id = registerRider("C" + i, city);
            ping(id, PICKUP_LAT + i * 0.0003, PICKUP_LON);
        }

        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            orders.add(UUID.randomUUID());
        }
        AtomicInteger next = new AtomicInteger();
        double conflictsBefore = counter("candidate.conflicts");
        LockWaitSampler sampler = new LockWaitSampler(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sampler.start();
        List<AssignmentService.Outcome> outcomes;
        try {
            // Any exception in a thread is rethrown here and fails the test.
            outcomes = concurrently(200, () -> assignments.onOrderCreated(
                    UUID.randomUUID(),
                    new OrderCreatedEvent(orders.get(next.getAndIncrement()), city, PICKUP_LAT, PICKUP_LON)));
        } finally {
            sampler.stop();
        }

        long assigned = outcomes.stream().filter(o -> o == AssignmentService.Outcome.ASSIGNED).count();
        long pending = outcomes.stream().filter(o -> o == AssignmentService.Outcome.PENDING).count();
        // Fails if two threads both won a rider for the same order, or if a rider was given to two orders.
        assertEquals(20, assigned, "exactly 20 threads get a rider");
        assertEquals(180, pending, "the other 180 are parked, none lost");

        String in = inClause(orders);
        assertEquals(20, count("SELECT count(*) FROM assignments WHERE order_id IN (" + in + ")", orders.toArray()));
        assertEquals(180, count("SELECT count(*) FROM pending_orders WHERE order_id IN (" + in + ")", orders.toArray()));
        assertEquals(20, count("SELECT count(*) FROM riders WHERE city = ? AND status = 'BUSY'", city));

        // Fails if no race happened. Candidates are read as AVAILABLE from Postgres first, so a conflict means
        // another transaction committed the same rider between that read and the conditional UPDATE: a real race.
        double conflicts = counter("candidate.conflicts") - conflictsBefore;
        assertTrue(conflicts > 0, "candidate.conflicts must increase: threads really raced for the same riders");
        // Evidence only, not asserted: the sampler polls pg_stat_activity every few milliseconds, so it can miss
        // short lock waits. A run can show zero samples even when conflicts happened.
        System.out.println("RACE-EVIDENCE conflicts=" + conflicts
                + " samplesWithRowLockWaits=" + sampler.samplesWithLockWaits());

        Invariants.assertHold(jdbc);
    }

    private ProducerRecord<String, String> orderCreatedRecord(UUID eventId, UUID orderId, String city) {
        String payload = String.format(
                "{\"orderId\":\"%s\",\"status\":\"CREATED\",\"version\":0,\"city\":\"%s\","
                        + "\"occurredAt\":\"%s\",\"pickupLat\":%s,\"pickupLon\":%s}",
                orderId, city, Instant.now(), PICKUP_LAT, PICKUP_LON);
        ProducerRecord<String, String> record = new ProducerRecord<>("order-events", orderId.toString(), payload);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", "OrderCreated".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** One producer; every record is sent before any is awaited, so the events arrive together. */
    private static void produceTogether(List<ProducerRecord<String, String>> records) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            List<Future<?>> sent = new ArrayList<>();
            for (ProducerRecord<String, String> record : records) {
                sent.add(producer.send(record));
            }
            for (Future<?> future : sent) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private static String inClause(List<?> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }

    /**
     * Samples pg_stat_activity in the background. A session waiting on a Lock is a transaction blocked on
     * another transaction's row lock. For this table, that is a real race on a rider row.
     */
    static final class LockWaitSampler {
        // Its own connection, not one from the pool: the 200 test threads occupy every pooled connection,
        // and a sampler that waited for a pooled connection would barely run.
        private final String url;
        private final String user;
        private final String password;
        private final AtomicLong samplesWithLockWaits = new AtomicLong();
        private volatile boolean running;
        private Thread thread;

        LockWaitSampler(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        void start() {
            running = true;
            thread = new Thread(() -> {
                try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, user, password)) {
                    while (running) {
                        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname = current_database() AND wait_event_type = 'Lock'
                                """);
                             java.sql.ResultSet rows = statement.executeQuery()) {
                            rows.next();
                            if (rows.getLong(1) > 0) {
                                samplesWithLockWaits.incrementAndGet();
                            }
                        }
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("lock-wait sampler could not query pg_stat_activity", e);
                }
            }, "lock-wait-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join(10_000);
        }

        long samplesWithLockWaits() {
            return samplesWithLockWaits.get();
        }
    }
}
