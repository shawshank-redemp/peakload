package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The breaker with an injected clock: no sleeping, time moves only when the test moves it. */
class CircuitBreakerTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<CircuitBreaker.State> transitions = new ArrayList<>();

    private CircuitBreaker breaker(boolean enabled) {
        // window 4, minimum 4 calls, threshold 50%, cool-down 10 s, 2 probes at once.
        var config = new ResilienceProperties.Circuit(enabled, 4, 4, 0.5, Duration.ofSeconds(10), 2);
        return new CircuitBreaker(config, nanos::get, transitions::add);
    }

    private void moveSeconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    void opensWhenTheFailureRateReachesTheThresholdAndNotBefore() {
        CircuitBreaker breaker = breaker(true);
        for (int i = 0; i < 3; i++) {
            assertTrue(breaker.tryAcquire());
            breaker.record(true);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "3 calls is below the minimum of 4");
        assertTrue(breaker.tryAcquire());
        breaker.record(true);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertEquals(List.of(CircuitBreaker.State.OPEN), transitions);
    }

    @Test
    void staysOpenForTheCoolDownWithoutAllowingCalls() {
        CircuitBreaker breaker = breaker(true);
        for (int i = 0; i < 4; i++) {
            breaker.tryAcquire();
            breaker.record(true);
        }
        moveSeconds(9);
        assertFalse(breaker.tryAcquire(), "still inside the cool-down: no call may go to the order service");
        assertEquals(1000, breaker.retryAfterMillis());
    }

    @Test
    void halfOpenAllowsOnlyTheConfiguredProbesThenASuccessCloses() {
        CircuitBreaker breaker = breaker(true);
        for (int i = 0; i < 4; i++) {
            breaker.tryAcquire();
            breaker.record(true);
        }
        moveSeconds(10);
        assertTrue(breaker.tryAcquire(), "the first probe after the cool-down");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire(), "the second probe");
        assertFalse(breaker.tryAcquire(), "no third probe at once");
        breaker.record(false);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(List.of(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED),
                transitions);
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void aFailedProbeReopensForANewCoolDown() {
        CircuitBreaker breaker = breaker(true);
        for (int i = 0; i < 4; i++) {
            breaker.tryAcquire();
            breaker.record(true);
        }
        moveSeconds(10);
        assertTrue(breaker.tryAcquire());
        breaker.record(true);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        moveSeconds(9);
        assertFalse(breaker.tryAcquire(), "a new cool-down started at the failed probe");
        moveSeconds(1);
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void successesInTheWindowKeepItClosed() {
        CircuitBreaker breaker = breaker(true);
        for (int i = 0; i < 20; i++) {
            assertTrue(breaker.tryAcquire());
            breaker.record(i % 4 == 0); // 25% failures, under the 50% threshold
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void disabledNeverRefusesAndNeverChanges() {
        CircuitBreaker breaker = breaker(false);
        for (int i = 0; i < 20; i++) {
            assertTrue(breaker.tryAcquire());
            breaker.record(true);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(transitions.isEmpty());
    }

    @Test
    void onlyUpstream502503504AreFailureStatuses() {
        for (int status : new int[] {400, 404, 409, 422, 429, 500, 200, 201}) {
            assertFalse(ProxyService.isUpstreamFailureStatus(status), "status " + status + " must not count");
        }
        for (int status : new int[] {502, 503, 504}) {
            assertTrue(ProxyService.isUpstreamFailureStatus(status), "status " + status + " must count");
        }
    }
}
