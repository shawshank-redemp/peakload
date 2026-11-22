package com.peakload.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry and circuit breaker on the order call. Every number here is an unmeasured starting value; the Week 5
 * measurement is what should change them, and that is not done yet.
 */
@ConfigurationProperties("peakload.admission.resilience")
public record ResilienceProperties(Retry retry, Circuit circuit) {

    /**
     * @param enabled      turns retries on or off (for the measurement)
     * @param deadline     the whole request, first attempt included, must finish within this
     * @param jitterMax    the delay before a retry is random in [0, jitterMax]
     * @param budgetShare  retries allowed as a share of order calls in the budget window (0.10 = 10%)
     * @param budgetWindow the rolling window for the budget
     */
    public record Retry(boolean enabled, Duration deadline, Duration jitterMax, double budgetShare, Duration budgetWindow) {}

    /**
     * @param enabled              turns the breaker on or off (for the measurement)
     * @param windowSize           the last this-many order calls are the sliding window
     * @param minimumCalls         the breaker cannot open before this many calls are in the window
     * @param failureRateThreshold the breaker opens when the failure rate in the window reaches this (0.5 = 50%)
     * @param openDuration         how long the breaker stays open before it allows probes
     * @param halfOpenProbes       how many probe calls may run at once while half-open
     */
    public record Circuit(boolean enabled, int windowSize, int minimumCalls, double failureRateThreshold,
                          Duration openDuration, int halfOpenProbes) {}
}
