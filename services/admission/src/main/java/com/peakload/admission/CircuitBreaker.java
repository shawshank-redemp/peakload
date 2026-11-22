package com.peakload.admission;

import java.util.function.LongSupplier;

/**
 * Circuit breaker for the order call. Count-based sliding window; open for a cool-down; half-open allows a few
 * probes at once; the first successful probe closes it, a failed probe opens it again.
 *
 * <p>Time comes from an injected clock (nanoseconds), so tests can move time without sleeping.
 * Thread-safe: every method is synchronized, and the calls are short.
 */
final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Called on every state change, with the new state. */
    interface Listener {
        void transitioned(State to);
    }

    private final ResilienceProperties.Circuit config;
    private final LongSupplier nanoClock;
    private final Listener listener;

    private State state = State.CLOSED;
    private final boolean[] window;      // true = failure
    private int filled;
    private int next;
    private int failures;
    private long openedAtNanos;
    private int probesInFlight;

    CircuitBreaker(ResilienceProperties.Circuit config, LongSupplier nanoClock, Listener listener) {
        this.config = config;
        this.nanoClock = nanoClock;
        this.listener = listener;
        this.window = new boolean[Math.max(1, config.windowSize())];
    }

    /** True if a call may be made now. A half-open call that is allowed counts as a probe until it is recorded. */
    synchronized boolean tryAcquire() {
        if (!config.enabled()) {
            return true;
        }
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (nanoClock.getAsLong() - openedAtNanos < config.openDuration().toNanos()) {
                    return false;
                }
                transition(State.HALF_OPEN);
                probesInFlight = 1;
                return true;
            case HALF_OPEN:
                if (probesInFlight >= config.halfOpenProbes()) {
                    return false;
                }
                probesInFlight++;
                return true;
            default:
                throw new IllegalStateException(state.name());
        }
    }

    /** Records the outcome of a call that was allowed by {@link #tryAcquire()}. */
    synchronized void record(boolean failure) {
        if (!config.enabled()) {
            return;
        }
        switch (state) {
            case CLOSED -> {
                pushOutcome(failure);
                if (filled >= config.minimumCalls()
                        && failures >= config.failureRateThreshold() * filled) {
                    open();
                }
            }
            case HALF_OPEN -> {
                if (failure) {
                    open();
                } else {
                    close();
                }
            }
            case OPEN -> {
                // A call that started before the breaker opened. It says nothing about the current state.
            }
        }
    }

    synchronized State state() {
        return state;
    }

    /** Milliseconds until the breaker may allow a probe, or 0 if it is not open. */
    synchronized long retryAfterMillis() {
        if (state != State.OPEN) {
            return 0;
        }
        long remaining = config.openDuration().toNanos() - (nanoClock.getAsLong() - openedAtNanos);
        return Math.max(0, remaining / 1_000_000L);
    }

    private void pushOutcome(boolean failure) {
        if (filled == window.length) {
            if (window[next]) {
                failures--;
            }
        } else {
            filled++;
        }
        window[next] = failure;
        if (failure) {
            failures++;
        }
        next = (next + 1) % window.length;
    }

    private void open() {
        openedAtNanos = nanoClock.getAsLong();
        probesInFlight = 0;
        transition(State.OPEN);
    }

    private void close() {
        clearWindow();
        probesInFlight = 0;
        transition(State.CLOSED);
    }

    private void clearWindow() {
        java.util.Arrays.fill(window, false);
        filled = 0;
        next = 0;
        failures = 0;
    }

    private void transition(State to) {
        if (state != to) {
            state = to;
            listener.transitioned(to);
        }
    }
}
