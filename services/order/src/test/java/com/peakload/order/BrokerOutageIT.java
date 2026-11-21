package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Broker outage, in two variants. Both keep the event in the outbox during the outage, deliver it
 * after recovery, and have the consumer process it once.
 *
 * <ul>
 *   <li><b>Cold</b> (runs first): the producer has never talked to the broker. Its first send happens
 *       during the outage, and send() blocks waiting for metadata. This is bounded by max.block.ms.</li>
 *   <li><b>Warm</b>: an event is published first, so the producer has metadata. The outage then hits
 *       the ack path: the send is accepted and the broker never acknowledges it, so delivery.timeout.ms
 *       fails it.</li>
 * </ul>
 *
 * <p>The class has its own context so the cold test's producer really is cold, and so no other live
 * relay can publish the outage event first. The cold test must run first, hence the @Order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrokerOutageIT extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry registry;

    @Test
    @Order(1)
    void coldProducerDuringOutageBlocksInSendThenRecovers() throws Exception {
        outageScenario(false);
    }

    @Test
    @Order(2)
    void warmProducerDuringOutageFailsAtAckThenRecovers() throws Exception {
        outageScenario(true);
    }

    private void outageScenario(boolean warmProducer) throws Exception {
        // Start from an empty backlog, so the backlog reading is exactly this scenario's event.
        awaitTrue("outbox backlog empty before the outage", Duration.ofSeconds(60),
                () -> unpublishedOutboxRows() == 0);

        if (warmProducer) {
            // Publish one event with the broker up, so the producer caches topic metadata.
            UUID warmOrder = createOrder(unique("warm"));
            awaitTrue("warm-up event published", Duration.ofSeconds(30),
                    () -> outboxEventsFor(warmOrder, OutboxWriter.ORDER_CREATED) == 1
                            && count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NULL",
                            warmOrder) == 0);
            awaitTrue("backlog empty after warm-up", Duration.ofSeconds(30),
                    () -> unpublishedOutboxRows() == 0);
        }

        String client = unique("client");
        double failuresBefore = registry.get("outbox.publish.failures").counter().count();
        UUID eventId;
        setBrokerPaused(true);
        try {
            // The order request does not talk to the broker, so it must still succeed.
            UUID orderId = createOrder(client);
            eventId = jdbc.queryForObject(
                    "SELECT id FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                    UUID.class, orderId, OutboxWriter.ORDER_CREATED);

            // Wait until the relay has recorded a failure, so the outage is really being exercised.
            awaitTrue("relay to record a publish failure during the outage", Duration.ofSeconds(60),
                    () -> registry.get("outbox.publish.failures").counter().count() > failuresBefore);

            // Fails if the row was marked published while the broker was unreachable.
            assertEquals(0, count("SELECT count(*) FROM outbox WHERE id = ? AND published_at IS NOT NULL", eventId));
            assertEquals(1.0, registry.get("outbox.backlog").gauge().value(),
                    "backlog must be exactly this one event");
        } finally {
            setBrokerPaused(false);
        }

        awaitTrue("event published after recovery", Duration.ofSeconds(90),
                () -> count("SELECT count(*) FROM outbox WHERE id = ? AND published_at IS NOT NULL", eventId) == 1);
        awaitTrue("event processed by the consumer", Duration.ofSeconds(60),
                () -> count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId) == 1);

        // Fails if the event has more than one copy on the topic: the timeout invariant means a failed
        // send must not reappear later.
        long copiesOnTopic = RelayIT.readAllFor(
                r -> eventId.toString().equals(RelayIT.header(r, OutboxRelay.HEADER_EVENT_ID)),
                Duration.ofSeconds(8)).size();
        assertEquals(1, copiesOnTopic, "the outage must not leave extra copies of the event on the topic");
        assertEquals(1, count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId));
    }
}
