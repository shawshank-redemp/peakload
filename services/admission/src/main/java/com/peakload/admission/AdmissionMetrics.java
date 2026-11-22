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

    /** A retry was sent to the order service. Labelled by class and by the reason the first attempt failed. */
    void retry(RequestClass requestClass, String reason) {
        Counter.builder("admission.upstream.retries")
                .description("Retries sent to the order service, by class and by the reason the first attempt failed")
                .tag("class", requestClass.name())
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** A retry was not sent. The reason says why: budget, load, or deadline. */
    void retrySkipped(String reason) {
        Counter.builder("admission.upstream.retries.skipped")
                .description("Retries that were allowed to be considered but not sent, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** A retry was refused because the retry budget was used up. */
    void retryBudgetExhausted() {
        Counter.builder("admission.retry.budget.exhausted")
                .description("Retries refused because the retry budget was used up")
                .register(registry)
                .increment();
    }

    /** The breaker changed state. The tag is the new state. */
    void circuitTransitioned(CircuitBreaker.State to) {
        Counter.builder("admission.circuit.transitions")
                .description("Order circuit breaker state changes, by the state entered")
                .tag("to", to.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /** Current breaker state as a number: 0 closed, 1 open, 2 half-open. */
    void registerCircuit(java.util.function.IntSupplier state) {
        Gauge.builder("admission.circuit.state", state::getAsInt)
                .description("Order circuit breaker state: 0 closed, 1 open, 2 half-open")
                .register(registry);
    }
}
