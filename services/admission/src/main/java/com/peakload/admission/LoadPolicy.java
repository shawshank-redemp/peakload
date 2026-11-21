package com.peakload.admission;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Decides the load level from two signals, and which request classes a level sheds. Pure: no clock, no metrics,
 * no state of its own. The caller keeps the {@link State} between decisions.
 *
 * <p>Two signals: in-flight work as a fraction of the upstream pool, and the smoothed upstream latency. Each has its
 * own level. The overall level is the higher of the two.
 *
 * <p>Hysteresis: a signal enters a level at the {@code enter} threshold, but leaves it only when it falls below the
 * lower {@code exit} threshold. A signal sitting between the two keeps its level, so the level does not flap.
 *
 * <p>Shedding order: LOW goes first (ELEVATED), then STANDARD (HIGH), then CRITICAL (HARD_CAP, the fail-fast cap).
 */
final class LoadPolicy {

    /** Thresholds for one level. Each exit value must be below its enter value. */
    record Band(double inFlightEnter, double inFlightExit, double latencyEnterMs, double latencyExitMs) {
        Band {
            if (!(inFlightEnter > 0 && inFlightExit > 0 && inFlightExit < inFlightEnter)) {
                throw new IllegalStateException("in-flight thresholds need 0 < exit < enter, got "
                        + inFlightExit + " and " + inFlightEnter);
            }
            if (!(latencyEnterMs > 0 && latencyExitMs > 0 && latencyExitMs < latencyEnterMs)) {
                throw new IllegalStateException("latency thresholds need 0 < exit < enter, got "
                        + latencyExitMs + " and " + latencyEnterMs);
            }
        }
    }

    /** The level each signal is at. Kept by the caller between decisions, because hysteresis depends on it. */
    record State(LoadLevel byInFlight, LoadLevel byLatency) {
        static final State START = new State(LoadLevel.NORMAL, LoadLevel.NORMAL);

        LoadLevel level() {
            return byInFlight.compareTo(byLatency) >= 0 ? byInFlight : byLatency;
        }
    }

    private static final List<LoadLevel> ABOVE_NORMAL_HIGHEST_FIRST =
            List.of(LoadLevel.HARD_CAP, LoadLevel.HIGH, LoadLevel.ELEVATED);

    private final Map<LoadLevel, Band> bands = new EnumMap<>(LoadLevel.class);

    LoadPolicy(Band elevated, Band high, Band hardCap) {
        bands.put(LoadLevel.ELEVATED, elevated);
        bands.put(LoadLevel.HIGH, high);
        bands.put(LoadLevel.HARD_CAP, hardCap);
    }

    /**
     * One decision.
     *
     * @param inFlightFraction in-flight requests divided by the upstream pool size
     * @param latencyMs        smoothed upstream latency, in milliseconds
     */
    State step(State previous, double inFlightFraction, double latencyMs) {
        return new State(
                levelFor(previous.byInFlight(), inFlightFraction, Band::inFlightEnter, Band::inFlightExit),
                levelFor(previous.byLatency(), latencyMs, Band::latencyEnterMs, Band::latencyExitMs));
    }

    /** True if a request of this class is shed at this level. */
    static boolean sheds(LoadLevel level, RequestClass requestClass) {
        return switch (level) {
            case NORMAL -> false;
            case ELEVATED -> requestClass == RequestClass.LOW;
            case HIGH -> requestClass != RequestClass.CRITICAL;
            case HARD_CAP -> true;
        };
    }

    /** The highest level whose threshold the value reaches. A level already held uses its lower exit threshold. */
    private LoadLevel levelFor(LoadLevel current, double value,
                               ToDoubleFunction<Band> enter, ToDoubleFunction<Band> exit) {
        for (LoadLevel candidate : ABOVE_NORMAL_HIGHEST_FIRST) {
            Band band = bands.get(candidate);
            boolean holding = current.compareTo(candidate) >= 0;
            double threshold = holding ? exit.applyAsDouble(band) : enter.applyAsDouble(band);
            if (value >= threshold) {
                return candidate;
            }
        }
        return LoadLevel.NORMAL;
    }

    /** Overload level, lowest to highest. The order is the severity. */
    enum LoadLevel {
        NORMAL,
        ELEVATED,
        HIGH,
        HARD_CAP
    }
}
