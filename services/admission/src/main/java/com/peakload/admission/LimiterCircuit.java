package com.peakload.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The circuit breaker in front of Redis. It decides, for each request, whether to ask Redis or to use the local
 * buckets.
 *
 * <ul>
 *   <li>Closed (mode redis): every request asks Redis. A failure opens the circuit.</li>
 *   <li>Open (mode local): requests use the local buckets and do not touch Redis, for the cool-down.</li>
 *   <li>After the cool-down, exactly one request is the probe and asks Redis. Its success closes the circuit;
 *       its failure opens it again for another cool-down. While a probe is in flight, other requests stay local.</li>
 * </ul>
 *
 * <p>With the breaker disabled, the circuit never opens: every request asks Redis, and a failure only affects that
 * request.
 *
 * <p>Mode changes are logged once per change, never per request.
 */
@Component
class LimiterCircuit {

    /** Where one request is decided. */
    enum Route { REDIS, PROBE, LOCAL }

    private static final Logger log = LoggerFactory.getLogger(LimiterCircuit.class);

    private final boolean enabled;
    private final long coolDownNanos;
    private final LongSupplier nanoClock;
    private final Counter opens;
    private final AtomicInteger modeValue = new AtomicInteger(0); // 0 = redis, 1 = local

    // Guarded by this.
    private boolean open;
    private long openUntilNanos;
    private boolean probeInFlight;

    @Autowired
    LimiterCircuit(RateLimitProperties properties, MeterRegistry registry) {
        this(properties.circuitBreaker(), System::nanoTime, registry);
    }

    LimiterCircuit(RateLimitProperties.CircuitBreaker config, LongSupplier nanoClock, MeterRegistry registry) {
        this.enabled = config.enabled();
        this.coolDownNanos = config.coolDown().toNanos();
        this.nanoClock = nanoClock;
        this.opens = Counter.builder("admission.limiter.circuit.opens")
                .description("Times the Redis circuit opened (including a probe that failed)")
                .register(registry);
        Gauge.builder("admission.limiter.mode", modeValue, AtomicInteger::get)
                .description("Rate-limiter mode: 0 = Redis, 1 = local fallback")
                .register(registry);
    }

    /** Decides where the next request goes. Call once per request, before talking to Redis. */
    synchronized Route acquire() {
        if (!enabled || !open) {
            return Route.REDIS;
        }
        if (nanoClock.getAsLong() - openUntilNanos >= 0 && !probeInFlight) {
            probeInFlight = true;
            return Route.PROBE;
        }
        return Route.LOCAL;
    }

    /** Called when a call to Redis succeeded. Closes the circuit if the call was the probe. */
    synchronized void redisSucceeded(Route route) {
        if (route != Route.PROBE) {
            return;
        }
        probeInFlight = false;
        if (open) {
            open = false;
            modeValue.set(0);
            log.info("rate limiter: Redis is back, switched to Redis mode");
        }
    }

    /** Called when a call to Redis failed or timed out. Opens the circuit, or re-opens it after a failed probe. */
    synchronized void redisFailed(Route route, RuntimeException cause) {
        if (route == Route.PROBE) {
            probeInFlight = false;
            openNow(cause, false);
        } else if (route == Route.REDIS && enabled && !open) {
            openNow(cause, true);
        }
    }

    private void openNow(RuntimeException cause, boolean modeChanged) {
        if (!enabled) {
            return;
        }
        open = true;
        openUntilNanos = nanoClock.getAsLong() + coolDownNanos;
        opens.increment();
        if (modeChanged) {
            modeValue.set(1);
            log.warn("rate limiter: Redis failed ({}), switched to local mode for {} ms",
                    cause.getClass().getSimpleName(), TimeUnit.NANOSECONDS.toMillis(coolDownNanos));
        } else {
            log.debug("rate limiter: probe to Redis failed, staying in local mode");
        }
    }

    /** 0 when Redis is in use, 1 when the local fallback is in use. */
    int mode() {
        return modeValue.get();
    }
}
