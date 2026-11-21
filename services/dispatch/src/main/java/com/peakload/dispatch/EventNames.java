package com.peakload.dispatch;

/** Topic names and Kafka header names shared by the consumer and the relay. */
final class EventNames {

    static final String ORDER_EVENTS = "order-events";
    static final String DISPATCH_EVENTS = "dispatch-events";

    /** Header carrying the event id (the outbox id, UUIDv7). Consumers deduplicate on it. */
    static final String EVENT_ID = "event-id";
    /** Header carrying the event type, e.g. OrderCreated or RiderAssigned. */
    static final String EVENT_TYPE = "event-type";

    private EventNames() {}
}
