package com.peakload.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/** The outbox relay publishes rows to the topic with the right key, headers and published_at. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RelayIT extends AbstractIntegrationTest {

    @Test
    void outboxRowIsPublishedWithOrderIdKeyAndEventHeaders() throws Exception {
        UUID orderId = createOrder(unique("client"));
        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                UUID.class, orderId, OutboxWriter.ORDER_CREATED);
        assertNotNull(eventId);

        // The relay runs on its schedule. Wait for it to mark the row published.
        awaitTrue("published_at set for event " + eventId, Duration.ofSeconds(30),
                () -> count("SELECT count(*) FROM outbox WHERE id = ? AND published_at IS NOT NULL", eventId) == 1);

        ConsumerRecord<String, String> record = readUntil(
                r -> eventId.toString().equals(header(r, OutboxRelay.HEADER_EVENT_ID)),
                Duration.ofSeconds(30)).stream().findFirst().orElseThrow();

        // Fails if the key is not the order id: events for one order must share a partition, in order.
        assertEquals(orderId.toString(), record.key());
        assertEquals(OutboxWriter.ORDER_CREATED, header(record, OutboxRelay.HEADER_EVENT_TYPE));
        assertEquals(eventId.toString(), header(record, OutboxRelay.HEADER_EVENT_ID));
        assertTrue(record.value().contains(orderId.toString()), "payload must carry the order id");
    }

    /** Reads the topic from the beginning with a fresh consumer group until a matching record arrives. */
    static List<ConsumerRecord<String, String>> readUntil(Predicate<ConsumerRecord<String, String>> match,
                                                          Duration timeout) {
        ConcurrentLinkedQueue<ConsumerRecord<String, String>> found = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = newTopicReader()) {
            while (System.nanoTime() < deadline && found.isEmpty()) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : batch) {
                    if (match.test(record)) {
                        found.add(record);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** Reads every record on the topic for a fixed settle time, and returns the ones that match. */
    static List<ConsumerRecord<String, String>> readAllFor(Predicate<ConsumerRecord<String, String>> match,
                                                           Duration settle) {
        List<ConsumerRecord<String, String>> matches = new java.util.ArrayList<>();
        long end = System.nanoTime() + settle.toNanos();
        try (KafkaConsumer<String, String> consumer = newTopicReader()) {
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(record)) {
                        matches.add(record);
                    }
                }
            }
        }
        return matches;
    }

    static KafkaConsumer<String, String> newTopicReader() {
        java.util.Properties props = new java.util.Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, AbstractIntegrationTest.REDPANDA.getBootstrapServers());
        // A fresh group id means no committed offset, so auto.offset.reset=earliest reads the whole topic.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(OutboxRelay.TOPIC));
        return consumer;
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
