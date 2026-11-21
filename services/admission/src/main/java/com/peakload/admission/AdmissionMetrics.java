package com.peakload.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Metrics for admission. Label values are fixed sets (request class, outcome, error type): no unbounded labels. */
@Component
class AdmissionMetrics {

    private final MeterRegistry registry;
    private final Map<RequestClass, AtomicInteger> inFlight = new EnumMap<>(RequestClass.class);
    private final Map<RequestClass, Timer> upstream = new EnumMap<>(RequestClass.class);
    private final Map<RequestClass, Counter> rateLimited = new EnumMap<>(RequestClass.class);
    private final Map<RequestClass, Counter> shed = new EnumMap<>(RequestClass.class);

    AdmissionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("admission.in.flight.total", this, AdmissionMetrics::inFlightTotal)
                .description("Requests currently being handled, all classes including local menu reads")
                .register(registry);
        for (RequestClass requestClass : RequestClass.values()) {
            AtomicInteger counter = new AtomicInteger();
            inFlight.put(requestClass, counter);
            Gauge.builder("admission.in.flight", counter, AtomicInteger::get)
                    .description("Requests currently being handled, by class")
                    .tag("class", requestClass.name())
                    .register(registry);
            shed.put(requestClass, Counter.builder("admission.shed")
                    .description("Requests rejected by load shedding (503), by class")
                    .tag("class", requestClass.name())
                    .register(registry));
            rateLimited.put(requestClass, Counter.builder("admission.rate.limited")
                    .description("Requests rejected by the rate limiter, by class")
                    .tag("class", requestClass.name())
                    .register(registry));
            upstream.put(requestClass, Timer.builder("admission.upstream")
                    .description("Time spent waiting for the upstream, by class")
                    .tag("class", requestClass.name())
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }

    void enter(RequestClass requestClass) {
        inFlight.get(requestClass).incrementAndGet();
    }

    void exit(RequestClass requestClass) {
        inFlight.get(requestClass).decrementAndGet();
    }

    /** In-flight requests across all classes, including local menu reads (they use the same Postgres). */
    int inFlightTotal() {
        int total = 0;
        for (AtomicInteger counter : inFlight.values()) {
            total += counter.get();
        }
        return total;
    }

    void shed(RequestClass requestClass) {
        shed.get(requestClass).increment();
    }

    /**
     * Counts a finished request. Outcome is one of: success, client_error, server_error, upstream_error,
     * not_found, rate_limited.
     */
    void requestFinished(String requestClass, String outcome) {
        Counter.builder("admission.requests")
                .description("Requests handled, by class and outcome")
                .tag("class", requestClass)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    Timer upstreamTimer(RequestClass requestClass) {
        return upstream.get(requestClass);
    }

    void rateLimited(RequestClass requestClass) {
        rateLimited.get(requestClass).increment();
    }

    void upstreamError(UpstreamException.Type type) {
        Counter.builder("admission.upstream.errors")
                .description("Upstream calls that failed, by error type")
                .tag("type", type.name().toLowerCase())
                .register(registry)
                .increment();
    }
}
