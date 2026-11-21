package com.peakload.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads "order-events" and reacts only to OrderCreated. Other event types are logged and ignored.
 *
 * <p>Consumer group "dispatch-order-events" keeps this service's read position on the topic, separate
 * from the other groups that read it. Malformed records are logged and skipped, since retrying the same
 * bytes cannot help. Database or Redis failures are not skipped: they propagate, and the error handler
 * retries the record, so an order is not lost while a dependency is down.
 */
@Component
class OrderEventListener {

    static final String GROUP = "dispatch-order-events";
    static final String ORDER_CREATED = "OrderCreated";

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final AssignmentService assignments;
    private final ObjectMapper mapper;
    private final Timer latency;
    private final Counter malformed;

    OrderEventListener(AssignmentService assignments, ObjectMapper mapper, MeterRegistry registry) {
        this.assignments = assignments;
        this.mapper = mapper;
        this.latency = Timer.builder("assignment.latency")
                .description("Time from receiving an OrderCreated event to the assignment being committed")
                .publishPercentileHistogram()
                .register(registry);
        this.malformed = Counter.builder("events.malformed")
                .description("Records skipped because they could not be parsed").register(registry);
    }

    @KafkaListener(topics = EventNames.ORDER_EVENTS, groupId = GROUP)
    void onEvent(ConsumerRecord<String, String> record) {
        String eventType = header(record, EventNames.EVENT_TYPE);
        if (!ORDER_CREATED.equals(eventType)) {
            log.debug("ignoring {} event at offset {}", eventType, record.offset());
            return;
        }

        UUID eventId;
        OrderCreatedEvent event;
        try {
            eventId = UUID.fromString(requireHeader(record, EventNames.EVENT_ID));
            event = OrderCreatedEvent.parse(mapper, record.value());
        } catch (IllegalArgumentException e) {
            malformed.increment();
            log.error("skipping malformed OrderCreated at offset {}: {}", record.offset(), e.getMessage());
            return;
        }

        long startedNanos = System.nanoTime();
        AssignmentService.Outcome outcome = assignments.onOrderCreated(eventId, event);
        if (outcome == AssignmentService.Outcome.ASSIGNED) {
            latency.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        }
        log.info("order {} event {}: {}", event.orderId(), eventId, outcome);
    }

    private static String requireHeader(ConsumerRecord<String, String> record, String name) {
        String value = header(record, name);
        if (value == null) {
            throw new IllegalArgumentException("record has no " + name + " header");
        }
        return value;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
