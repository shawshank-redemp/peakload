package com.peakload.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakload.admission.LoadPolicy.Band;
import com.peakload.admission.LoadPolicy.LoadLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Controller decisions with the signals supplied by the test. */
class LoadControllerTest {

    private static final int POOL = 200;

    private final LoadPolicy policy = new LoadPolicy(
            new Band(0.50, 0.40, 250, 150),
            new Band(0.75, 0.60, 1000, 600),
            new Band(0.95, 0.85, 2500, 1500));

    @Test
    void disabledNeverSheds() {
        AtomicInteger inFlight = new AtomicInteger(199); // 99.5% of the pool: HARD_CAP if enabled
        LoadController controller = new LoadController(false, policy, POOL, inFlight::get, () -> 5000,
                new SimpleMeterRegistry());

        for (RequestClass c : RequestClass.values()) {
            assertThat(controller.check(c).shed()).as("disabled sheds " + c).isFalse();
        }
    }

    @Test
    void disabledStillComputesTheLevelForTheGauge() {
        AtomicInteger inFlight = new AtomicInteger(199);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoadController controller = new LoadController(false, policy, POOL, inFlight::get, () -> 0, registry);

        LoadController.Decision decision = controller.check(RequestClass.CRITICAL);

        assertThat(decision.shed()).isFalse();
        assertThat(decision.level()).isEqualTo(LoadLevel.HARD_CAP);
        assertThat(registry.get("admission.load.level").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void secondsAtEachLevelAreCountedFromDecisionTimes() {
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(0);
        long s = 1_000_000_000L;
        LoadController controller = new LoadController(true, policy, POOL, inFlight::get, () -> 0, clock::get,
                new SimpleMeterRegistry());

        controller.check(RequestClass.LOW);          // t=0: NORMAL from here
        clock.set(1 * s);
        inFlight.set(120);                           // t=1: ELEVATED from the next decision
        controller.check(RequestClass.LOW);          // counts 1 s of NORMAL
        clock.set(3 * s);
        controller.check(RequestClass.LOW);          // counts 2 s of ELEVATED
        clock.set(4 * s);
        inFlight.set(0);
        controller.check(RequestClass.LOW);          // counts 1 s of ELEVATED; the level falls to NORMAL

        assertThat(controller.secondsAt(LoadLevel.NORMAL)).isEqualTo(1.0);
        assertThat(controller.secondsAt(LoadLevel.ELEVATED)).isEqualTo(3.0);
        assertThat(controller.secondsAt(LoadLevel.HIGH)).isEqualTo(0.0);
    }

    @Test
    void enabledShedsByInFlightFraction() {
        AtomicInteger inFlight = new AtomicInteger(0);
        LoadController controller = new LoadController(true, policy, POOL, inFlight::get, () -> 0,
                new SimpleMeterRegistry());

        inFlight.set(20); // 10%: NORMAL
        assertThat(controller.check(RequestClass.LOW).shed()).isFalse();

        inFlight.set(120); // 60%: ELEVATED sheds LOW only
        assertThat(controller.check(RequestClass.LOW).shed()).isTrue();
        assertThat(controller.check(RequestClass.STANDARD).shed()).isFalse();
        assertThat(controller.check(RequestClass.CRITICAL).shed()).isFalse();

        inFlight.set(190); // 95%: HARD_CAP sheds everything
        LoadController.Decision decision = controller.check(RequestClass.CRITICAL);
        assertThat(decision.shed()).isTrue();
        assertThat(decision.level()).isEqualTo(LoadLevel.HARD_CAP);
    }

    @Test
    void enabledShedsByLatency() {
        AtomicReference<Double> latency = new AtomicReference<>(0.0);
        LoadController controller = new LoadController(true, policy, POOL, () -> 0, latency::get,
                new SimpleMeterRegistry());

        latency.set(1200.0); // HIGH: sheds STANDARD, keeps CRITICAL
        assertThat(controller.check(RequestClass.STANDARD).shed()).isTrue();
        assertThat(controller.check(RequestClass.CRITICAL).shed()).isFalse();
    }

    @Test
    void levelGaugeFollowsTheLastDecision() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger inFlight = new AtomicInteger(0);
        LoadController controller = new LoadController(true, policy, POOL, inFlight::get, () -> 0, registry);

        inFlight.set(120);
        controller.check(RequestClass.LOW);
        assertThat(registry.get("admission.load.level").gauge().value()).isEqualTo(1.0);
    }
}
