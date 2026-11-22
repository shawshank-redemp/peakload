package com.peakload.admission;

import com.peakload.admission.LoadPolicy.LoadLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns the two live signals into a load level for each request, and says whether that request is shed.
 *
 * <p>The level is re-evaluated on every request, so the policy's hysteresis state moves with the traffic. When no
 * requests arrive, the level gauge keeps its last value.
 *
 * <p>Time in each level is counted exactly, from the clock, not from a sampler. Each request adds the time since the
 * previous decision to the level that was in effect over that time. The total per level is therefore the time the
 * process spent at that level, with the precision of the gap between decisions. A quiet period with no requests is
 * counted at the level of the last decision, because no decision was made during it.
 */
@Component
class LoadController {

    /** The result for one request. */
    record Decision(boolean shed, LoadLevel level) {}

    private final LoadPolicy policy;
    private final boolean enabled;
    private final int poolSize;
    private final IntSupplier inFlight;
    private final DoubleSupplier latencyMillis;
    private final LongSupplier nanoClock;
    private final Map<LoadLevel, Counter> secondsAtLevel = new EnumMap<>(LoadLevel.class);

    private LoadPolicy.State state = LoadPolicy.State.START;
    private volatile LoadLevel level = LoadLevel.NORMAL;
    private long lastDecisionNanos = -1;

    @Autowired
    LoadController(ShedProperties properties, AdmissionProperties admission, AdmissionMetrics metrics,
                   UpstreamLatency latency, MeterRegistry registry) {
        this(properties.enabled(), properties.policy(), admission.upstream().maxConnections(),
                metrics::inFlightTotal, latency::valueMillis, System::nanoTime, registry);
    }

    LoadController(boolean enabled, LoadPolicy policy, int poolSize, IntSupplier inFlight,
                   DoubleSupplier latencyMillis, MeterRegistry registry) {
        this(enabled, policy, poolSize, inFlight, latencyMillis, System::nanoTime, registry);
    }

    LoadController(boolean enabled, LoadPolicy policy, int poolSize, IntSupplier inFlight,
                   DoubleSupplier latencyMillis, LongSupplier nanoClock, MeterRegistry registry) {
        this.enabled = enabled;
        this.policy = policy;
        this.poolSize = poolSize;
        this.inFlight = inFlight;
        this.latencyMillis = latencyMillis;
        this.nanoClock = nanoClock;
        Gauge.builder("admission.load.level", this, c -> c.level.ordinal())
                .description("Load level: 0 NORMAL, 1 ELEVATED (sheds LOW), 2 HIGH (sheds LOW, STANDARD), "
                        + "3 HARD_CAP (sheds all)")
                .register(registry);
        for (LoadLevel candidate : LoadLevel.values()) {
            secondsAtLevel.put(candidate, Counter.builder("admission.load.level.seconds")
                    .description("Seconds spent at each load level, counted from decision times")
                    .tag("level", candidate.name())
                    .register(registry));
        }
    }

    /** Decides for one request of the given class. Called after the rate limiter, before any upstream call. */
    synchronized Decision check(RequestClass requestClass) {
        long now = nanoClock.getAsLong();
        if (lastDecisionNanos >= 0) {
            // The time since the last decision was spent at the level that was in effect until now.
            secondsAtLevel.get(level).increment((now - lastDecisionNanos) / 1_000_000_000d);
        }
        lastDecisionNanos = now;

        // The level is computed even when shedding is off, so the gauge still shows the load in comparison runs.
        double fraction = inFlight.getAsInt() / (double) poolSize;
        state = policy.step(state, fraction, latencyMillis.getAsDouble());
        level = state.level();
        return new Decision(enabled && LoadPolicy.sheds(level, requestClass), level);
    }

    /** Seconds counted at this level so far, from the decision times. For tests and diagnostics. */
    double secondsAt(LoadLevel candidate) {
        return secondsAtLevel.get(candidate).count();
    }

    /** The level as of the last decision. Used by the retry rules: retries only at NORMAL. */
    LoadLevel currentLevel() {
        return level;
    }
}
