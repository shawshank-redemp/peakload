package com.peakload.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code peakload.dispatch} in application.yml. */
@ConfigurationProperties("peakload.dispatch")
public record DispatchProperties(Location location, Assignment assignment, Pending pending, Relay relay) {

    /**
     * @param lastSeenTtl how long a location ping keeps the rider's seen key. Redis expires keys in whole
     *                    seconds, so the value must be at least one second.
     */
    public record Location(Duration lastSeenTtl) {}

    /**
     * @param radiusKm     search radius around the pickup point
     * @param maxCandidates how many fresh candidates to try, nearest first
     */
    public record Assignment(double radiusKm, int maxCandidates) {}

    /**
     * @param retryInterval pause between retry runs over pending orders
     * @param maxAge        after this age a still-unassigned order is given up, logged and counted
     * @param batchSize     pending orders retried per run
     */
    public record Pending(Duration retryInterval, Duration maxAge, int batchSize) {}

    /**
     * @param batchSize outbox rows per relay batch
     * @param sendWait   longest wait for one broker ack; must exceed the producer's delivery.timeout.ms
     */
    public record Relay(int batchSize, Duration sendWait) {}
}
