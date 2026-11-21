package com.peakload.admission;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Smoothed latency of calls to the order service, as an exponentially weighted moving average (EWMA).
 *
 * <p>Every upstream call adds a sample, including calls that end in a timeout (their elapsed time is still
 * evidence of slowness). The value also fades with time: it halves every {@code halfLife} without a new sample.
 * Without the fade, a high value would stay high after the load stopped, because shed requests never reach the
 * order service and so never add a sample to bring it back down.
 *
 * <p>The fade is a design choice, not a measurement. When it runs out, admission lets a request through to test
 * whether the upstream has recovered, so a recovered upstream is seen again within a few half-lives.
 */
@Component
class UpstreamLatency {

    private final double smoothing;
    private final double halfLifeNanos;
    private final LongSupplier nanoClock;

    private double ewmaMillis;
    private long lastSampleNanos;
    private boolean sampled;

    @Autowired
    UpstreamLatency(ShedProperties properties, MeterRegistry registry) {
        this(properties.smoothing(), properties.latencyHalfLife(), System::nanoTime, registry);
    }

    UpstreamLatency(double smoothing, Duration halfLife, LongSupplier nanoClock, MeterRegistry registry) {
        this.smoothing = smoothing;
        this.halfLifeNanos = halfLife.toNanos();
        this.nanoClock = nanoClock;
        Gauge.builder("admission.upstream.latency.ewma.ms", this, UpstreamLatency::valueMillis)
                .description("Smoothed latency of order-service calls, in ms; fades when no calls are made")
                .register(registry);
    }

    /** Adds one upstream call's elapsed time. */
    synchronized void record(long elapsedMillis) {
        long now = nanoClock.getAsLong();
        // Fade the previous value only up to the moment this call started. Time spent inside a call is not idle
        // time: without this, back-to-back slow calls would decay the value between samples.
        long callStart = now - elapsedMillis * 1_000_000L;
        double previous = valueAt(callStart);
        // A first sample sets the value. Later ones move it by the smoothing weight.
        ewmaMillis = sampled ? previous + smoothing * (elapsedMillis - previous) : elapsedMillis;
        lastSampleNanos = now;
        sampled = true;
    }

    /** The current smoothed value in ms, after fading. Zero before any sample. */
    synchronized double valueMillis() {
        return valueAt(nanoClock.getAsLong());
    }

    private double valueAt(long now) {
        if (!sampled) {
            return 0;
        }
        double age = Math.max(0, now - lastSampleNanos);
        return ewmaMillis * Math.pow(0.5, age / halfLifeNanos);
    }
}
