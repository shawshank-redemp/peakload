package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Case 9: the relay dies after the broker acknowledges a send but before it sets published_at.
 *
 * <p>The scheduled relay is effectively turned off (interval one hour), so the test controls
 * every relay run. The test publishes the event itself with the same event-id and headers the relay
 * would use, and leaves the outbox row unpublished. That is the state a crashed relay would leave.
 * Then it runs the relay. The result must be two copies on the topic, one processed event, and one
 * duplicate counted. This class has its own context so the relay timing is fully controlled.
 */
@TestPropertySource(properties = "peakload.outbox.relay.interval=PT1H")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RelayRecoveryIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private MeterRegistry registry;

    @Test
    void relayDyingAfterAckLeavesDuplicateThatTheConsumerIgnores() throws Exception {
        UUID orderId = createOrder(unique("client"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, payload::text AS payload FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                orderId, OutboxWriter.ORDER_CREATED);
        UUID eventId = (UUID) row.get("id");
        String payload = (String) row.get("payload");
        assertNotNull(eventId);
        double duplicatesBefore = registry.get("events.duplicates.ignored").counter().count();

        // 1. The "dying" relay: the broker acknowledges the event, then the relay crashes before the
        //    UPDATE that sets published_at.
        try (KafkaProducer<String, String> producer = producer()) {
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, orderId.toString(), payload);
            record.headers().add(OutboxRelay.HEADER_EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(OutboxRelay.HEADER_EVENT_TYPE,
                    OutboxWriter.ORDER_CREATED.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        // The crashed relay never set published_at, so the row is still unpublished.
        assertEquals(0, count("SELECT count(*) FROM outbox WHERE id = ? AND published_at IS NOT NULL", eventId),
                "precondition: the row must still look unpublished");

        // 2. The restarted relay runs and publishes the same row again.
        relay.relay();
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE id = ? AND published_at IS NOT NULL", eventId),
                "the relay must mark the row published after the second send is acknowledged");

        // 3. The consumer sees both copies. The first is processed; the second is a duplicate.
        awaitTrue("duplicate counted", Duration.ofSeconds(60),
                () -> registry.get("events.duplicates.ignored").counter().count() >= duplicatesBefore + 1);

        // Fails if the event was not recorded as processed.
        assertEquals(1, count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId));
        // Fails if the duplicate was not counted, or if more than one duplicate appeared.
        assertEquals(duplicatesBefore + 1,
                registry.get("events.duplicates.ignored").counter().count(), 0.0,
                "exactly one duplicate must be counted");

        // Fails if the event was lost: the topic must hold both copies with the same event id.
        long copies = RelayIT.readAllFor(
                r -> eventId.toString().equals(RelayIT.header(r, OutboxRelay.HEADER_EVENT_ID)),
                Duration.ofSeconds(8)).size();
        assertEquals(2, copies, "two copies with the same event-id must be on the topic");
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }
}
